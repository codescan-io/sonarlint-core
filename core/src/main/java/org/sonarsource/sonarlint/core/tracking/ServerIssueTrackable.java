/*
 * SonarLint Core - Implementation
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
package org.sonarsource.sonarlint.core.tracking;

import org.sonarsource.sonarlint.core.commons.HotspotReviewStatus;
import org.sonarsource.sonarlint.core.commons.IssueSeverity;
import org.sonarsource.sonarlint.core.commons.RuleType;
import org.sonarsource.sonarlint.core.commons.TextRangeWithHash;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;
import org.sonarsource.sonarlint.core.issuetracking.Trackable;
import org.sonarsource.sonarlint.core.serverconnection.issues.LineLevelServerIssue;
import org.sonarsource.sonarlint.core.serverconnection.issues.RangeLevelServerIssue;
import org.sonarsource.sonarlint.core.serverconnection.issues.ServerIssue;

public class ServerIssueTrackable implements Trackable {

  private final ServerIssue serverIssue;
  private static final SonarLintLogger LOG = SonarLintLogger.get();

  public ServerIssueTrackable(ServerIssue serverIssue) {
    this.serverIssue = serverIssue;
  }

  @Override
  public Object getClientObject() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRuleKey() {
    return serverIssue.getRuleKey();
  }

  @Override
  public IssueSeverity getSeverity() {
    var userSeverity = serverIssue.getUserSeverity();
    return userSeverity != null ? userSeverity : null;
  }

  @Override
  public RuleType getType() {
    return serverIssue.getType();
  }

  @Override
  public String getMessage() {
    return serverIssue.getMessage();
  }

  @Override
  public Integer getLine() {
    if (serverIssue instanceof LineLevelServerIssue) {
      LOG.info("ServerIssue type: {}, line: {}", serverIssue.getClass().getSimpleName(), ((LineLevelServerIssue) serverIssue).getLine());
      return ((LineLevelServerIssue) serverIssue).getLine();
    }
    if (serverIssue instanceof RangeLevelServerIssue) {
      LOG.info("ServerIssue type: {}, line: {}", serverIssue.getClass().getSimpleName(), ((RangeLevelServerIssue) serverIssue).getTextRange().getStartLine());
      return ((RangeLevelServerIssue) serverIssue).getTextRange().getStartLine();
    }
    return null;
  }

  @Override
  public String getLineHash() {
    if (serverIssue instanceof LineLevelServerIssue) {
      return ((LineLevelServerIssue) serverIssue).getLineHash();
    }
    return null;
  }

  @Override
  public TextRangeWithHash getTextRange() {
    if (serverIssue instanceof RangeLevelServerIssue) {
      return ((RangeLevelServerIssue) serverIssue).getTextRange();
    }
    if (serverIssue instanceof LineLevelServerIssue) {
      // Create a TextRangeWithHash for line-level issues
      // Line-level issues span the entire line (offset 0 to end of line)
      var lineLevelIssue = (LineLevelServerIssue) serverIssue;
      var line = lineLevelIssue.getLine();
      var lineHash = lineLevelIssue.getLineHash();
      if (line != null && lineHash != null) {
        // Create a text range that spans the entire line
        // Start offset 0, end offset -1 (end of line)
        // For line-level issues, the text range hash should be the same as the line hash
        // since the text range spans the entire line
        var textRange = new org.sonarsource.sonarlint.core.commons.TextRange(line, 0, line, -1);
        return IssueTrackable.convertToTrackingTextRange(textRange, lineHash);
      }
    }
    return null;
  }

  @Override
  public Long getCreationDate() {
    return serverIssue.getCreationDate().toEpochMilli();
  }

  @Override
  public String getServerIssueKey() {
    LOG.info("issue key after resolutions "+serverIssue.getKey());
    return serverIssue.getKey();
  }

  @Override
  public boolean isResolved() {
    LOG.info("is issue Resolved.... after resolutions "+serverIssue.isResolved());
    return serverIssue.isResolved();
  }

  @Override
  public HotspotReviewStatus getReviewStatus() {
    return null;
  }
}
