/*
 * SonarLint Core - Server Connection
 * Copyright (C) 2016-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.core.serverconnection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.sonar.scanner.protocol.input.ScannerInput;
import org.sonarsource.sonarlint.core.commons.IssueSeverity;
import org.sonarsource.sonarlint.core.commons.Language;
import org.sonarsource.sonarlint.core.commons.RuleType;
import org.sonarsource.sonarlint.core.commons.TextRangeWithHash;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;
import org.sonarsource.sonarlint.core.serverapi.ServerApi;
import org.sonarsource.sonarlint.core.serverapi.proto.sonarqube.ws.Issues;
import org.sonarsource.sonarlint.core.serverapi.proto.sonarqube.ws.Issues.IssueLite;
import org.sonarsource.sonarlint.core.serverapi.rules.RulesApi;
import org.sonarsource.sonarlint.core.serverconnection.issues.FileLevelServerIssue;
import org.sonarsource.sonarlint.core.serverconnection.issues.LineLevelServerIssue;
import org.sonarsource.sonarlint.core.serverconnection.issues.RangeLevelServerIssue;
import org.sonarsource.sonarlint.core.serverconnection.issues.ServerIssue;

import static java.util.function.Predicate.not;

public class IssueDownloader {

  private final Set<Language> enabledLanguages;
  private static final SonarLintLogger LOG = SonarLintLogger.get();

  public IssueDownloader(Set<Language> enabledLanguages) {
    this.enabledLanguages = enabledLanguages;
  }

  /**
   * Fetch all issues of the component with specified key.
   * If the component doesn't exist or it exists but has no issues, an empty iterator is returned.
   *
   * @param key project key, or file key.
   * @param branchName name of the branch.
   * @return List of issues. It can be empty but never null.
   */
  public List<ServerIssue> downloadFromBatch(ServerApi serverApi, String key, @Nullable String branchName) {
    var issueApi = serverApi.issue();

    List<ServerIssue> result = new ArrayList<>();

    var batchIssues = issueApi.downloadAllFromBatchIssues(key, branchName);
LOG.info("Downloaded {} issues from batch for project {} on branch {}", batchIssues.size(), key, branchName);
    for (ScannerInput.ServerIssue batchIssue : batchIssues) {
      LOG.info("Processing batch issue: {}", batchIssue);
      // We ignore project level issues
      if (!RulesApi.TAINT_REPOS.contains(batchIssue.getRuleRepository()) && batchIssue.hasPath()) {
        result.add(convertBatchIssue(batchIssue));
      }
    }
LOG.info("After filtering project level issues, {} issues remain for project {} and result ", result.size(), key,result);
    return result;
  }

  /**
   * Fetch all issues of the project with specified key, using new SQ 9.6 api/issues/pull
   *
   * @param projectKey project key
   * @param branchName name of the branch.
   * @return List of issues. It can be empty but never null.
   */
  public PullResult downloadFromPull(ServerApi serverApi, String projectKey, String branchName, Optional<Instant> lastSync) {
    var issueApi = serverApi.issue();
LOG.info("Pulling issues from server for project {} on branch {} since {}", projectKey, branchName, lastSync);
    var apiResult = issueApi.pullIssues(projectKey, branchName, enabledLanguages, lastSync.map(Instant::toEpochMilli).orElse(null));
    LOG.info("Pulled {} issues ({} closed) from server for project {}", apiResult, apiResult, projectKey);
    // Ignore project level issues
    var changedIssues = apiResult.getIssues()
      .stream()
      // Ignore project level issues
      .filter(i -> i.getMainLocation().hasFilePath())
      .filter(not(IssueLite::getClosed))
      .map(IssueDownloader::convertLiteIssue)
      .collect(Collectors.toList());
    var closedIssueKeys = apiResult.getIssues()
      .stream()
      // Ignore project level issues
      .filter(i -> i.getMainLocation().hasFilePath())
      .filter(IssueLite::getClosed)
      .map(IssueLite::getKey)
      .collect(Collectors.toSet());
    LOG.info("After filtering project level issues, {} issues remain ({} closed) for project {} and isue are {}", changedIssues.size() + closedIssueKeys.size(),
      closedIssueKeys.size(), projectKey,changedIssues);

    return new PullResult(Instant.ofEpochMilli(apiResult.getTimestamp().getQueryTimestamp()), changedIssues, closedIssueKeys);
  }

  private static ServerIssue convertBatchIssue(ScannerInput.ServerIssue batch) {
    var ruleKey      = batch.getRuleRepository() + ":" + batch.getRuleKey();
    var filePath     = batch.getPath();
    var creationDate = Instant.ofEpochMilli(batch.getCreationDate());
    var userSeverity = batch.getManualSeverity() ? IssueSeverity.valueOf(batch.getSeverity().name()) : null;
    var ruleType     = RuleType.valueOf(batch.getType());

    String repo = batch.getRuleRepository();
    String keyFromServer = batch.getRuleKey(); // may already be "sf:AvoidSoqlInLoops" in some payloads

    if (keyFromServer != null && keyFromServer.contains(":")) {
      // Already fully-qualified on the wire; just strip a possible leading colon
      ruleKey = keyFromServer.startsWith(":") ? keyFromServer.substring(1) : keyFromServer;
    } else if (repo != null && !repo.isBlank()) {
      ruleKey = repo + ":" + keyFromServer;
    } else {
      // Fallback: just the key as-is
      ruleKey = keyFromServer;
    }

LOG.info("convertBatchIssue: key={}, ruleKey={}, filePath={}, creationDate={}, userSeverity={}, ruleType={}",
      batch.getKey(), ruleKey, filePath, creationDate, userSeverity, ruleType);
    // Map server resolution -> boolean resolved
    String resolution = batch.hasResolution() ? batch.getResolution() : null;
    LOG.info("convertBatchIssue: resolution={}", resolution);
    var status = batch.hasStatus() ? batch.getStatus() : null;
    LOG.info("convertBatchIssue: status={}", status);

    boolean isResolved =
            "FALSE-POSITIVE".equals(resolution)
                    || "WONTFIX".equals(resolution)
                    || "FIXED".equals(resolution)
                    || "CONFIRM".equals(resolution);

    // Prefer server checksum as the pairing fingerprint; otherwise compute a stable fallback
    String lineHash = (batch.getChecksum() != null && !batch.getChecksum().isEmpty())
            ? batch.getChecksum()
            : fallbackLineHash(ruleKey, batch.getMsg(), batch.hasLine() ? batch.getLine() : null);

    LOG.info("convertBatchIssue: key={}, resolution={}, resolved={}", batch.getKey(), resolution, isResolved);

    if (batch.hasLine()) {
      LOG.info("convertBatchIssue: Creating LineLevelServerIssue for key={}, lineHash={}", batch.getKey(), lineHash);
      return new LineLevelServerIssue(
              batch.getKey(), isResolved, ruleKey, batch.getMsg(), lineHash,
              filePath, creationDate, userSeverity, ruleType, batch.getLine()
      );
    } else {
      LOG.info("convertBatchIssue: Creating FileLevelServerIssue for key={}", batch.getKey());
      return new FileLevelServerIssue(
              batch.getKey(), isResolved, ruleKey, batch.getMsg(),
              filePath, creationDate, userSeverity, ruleType
      );
    }
  }

  private static String fallbackLineHash(String ruleKey, String msg, Integer line) {
    LOG.info("Computing fallback line hash for ruleKey={}, line={}", ruleKey, line);
    String normalizedMsg = msg == null ? "" : msg.replaceAll("\\s+", " ").trim().toLowerCase();
    String payload = ruleKey + "|" + normalizedMsg + "|" + (line == null ? "" : line.toString());
    try {
      var md = java.security.MessageDigest.getInstance("SHA-1");
      byte[] dig = md.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var sb = new StringBuilder(dig.length * 2);
      for (byte b : dig) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      return Integer.toHexString(payload.hashCode());
    }
  }


  private static ServerIssue convertLiteIssue(IssueLite liteIssueFromWs) {
    var mainLocation = liteIssueFromWs.getMainLocation();
    var filePath = mainLocation.getFilePath();
    var creationDate = Instant.ofEpochMilli(liteIssueFromWs.getCreationDate());
    var userSeverity = liteIssueFromWs.hasUserSeverity() ? IssueSeverity.valueOf(liteIssueFromWs.getUserSeverity().name()) : null;
    var ruleType = RuleType.valueOf(liteIssueFromWs.getType().name());
    String resolutionLite = null; // TODO: Add support when IssueLite provides resolution
    String statusLite = null;     // TODO: Add support when IssueLite provides status
    LOG.info("convertLiteIssue: key=" + liteIssueFromWs.getKey() + ", resolution=" + resolutionLite + ", status=" + statusLite);
    if (mainLocation.hasTextRange()) {
      LOG.info("convertLiteIssue: Creating RangeLevelServerIssue for key=" + liteIssueFromWs.getKey());
      return new RangeLevelServerIssue(liteIssueFromWs.getKey(), liteIssueFromWs.getResolved(), liteIssueFromWs.getRuleKey(), mainLocation.getMessage(),
        filePath, creationDate, userSeverity,
        ruleType, toServerIssueTextRange(mainLocation.getTextRange()));
    } else {
      LOG.info("convertLiteIssue: Creating FileLevelServerIssue for key=" + liteIssueFromWs.getKey());
      return new FileLevelServerIssue(liteIssueFromWs.getKey(), liteIssueFromWs.getResolved(), liteIssueFromWs.getRuleKey(), mainLocation.getMessage(),
        filePath, creationDate, userSeverity, ruleType);
    }
  }

  private static TextRangeWithHash toServerIssueTextRange(Issues.TextRange textRange) {
    return new TextRangeWithHash(textRange.getStartLine(), textRange.getStartLineOffset(), textRange.getEndLine(), textRange.getEndLineOffset(), textRange.getHash());
  }

  public static class PullResult {
    private final Instant queryTimestamp;
    private final List<ServerIssue> changedIssues;
    private final Set<String> closedIssueKeys;

    public PullResult(Instant queryTimestamp, List<ServerIssue> changedIssues, Set<String> closedIssueKeys) {
      this.queryTimestamp = queryTimestamp;
      this.changedIssues = changedIssues;
      this.closedIssueKeys = closedIssueKeys;
    }

    public Instant getQueryTimestamp() {
      return queryTimestamp;
    }

    public List<ServerIssue> getChangedIssues() {
      return changedIssues;
    }

    public Set<String> getClosedIssueKeys() {
      return closedIssueKeys;
    }
  }

}
