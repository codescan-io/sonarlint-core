/*
 * SonarLint Issue Tracking
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
package org.sonarsource.sonarlint.core.issuetracking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;

/**
 * Match and track a collection of issues.
 *
 * @param <R> type of the "raw" trackables that are in the incoming collection
 * @param <B> type of the base trackables that are in the current collection
 */
public class Tracker<R extends Trackable, B extends Trackable> {
  private static final SonarLintLogger LOG = SonarLintLogger.get();

  private static String normalizeRule(String rk) {
    LOG.info("Normalizing rule key in tracker: {}", rk);
    if (rk == null) return null;
    LOG.info("Normalized rule key in tracker: {}", rk.startsWith(":") ? rk.substring(1) : rk);
    return rk.startsWith(":") ? rk.substring(1) : rk;
  }

  private static String normMsg(String s) {
    LOG.info("Normalizing message in tracker: {}", s);
    LOG.info("Normalized message in tracker: {}", s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase());
    return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase();
  }


  public Tracking<R, B> track(Supplier<Collection<R>> rawTrackableSupplier, Supplier<Collection<B>> baseTrackableSupplier) {
    var tracking = new Tracking<>(rawTrackableSupplier, baseTrackableSupplier);

    LOG.info(">>>>Tracking : {} {}", tracking.getMatchedRaws().size(), tracking.isComplete());
    // 1. match issues with same server issue key
    match(tracking, ServerIssueSearchKeyFactory.INSTANCE);

    // 2. match issues with same rule, same line and same text range hash, but not necessarily with same message
    match(tracking, LineAndTextRangeHashKeyFactory.INSTANCE);

    // 3. match issues with same rule, same message and same text range hash
    match(tracking, TextRangeHashAndMessageKeyFactory.INSTANCE);

    // 4. match issues with same rule, same line and same message
    match(tracking, LineAndMessageKeyFactory.INSTANCE);

    // 5. match issues with same rule and same text range hash but different line and different message.
    // See SONAR-2812
    match(tracking, TextRangeHashKeyFactory.INSTANCE);

    // 6. match issues with same rule, same line and same line hash
    match(tracking, LineAndLineHashKeyFactory.INSTANCE);

    // 7. match issues with same rule and same same line hash
    match(tracking, LineHashKeyFactory.INSTANCE);

    LOG.info(">>>>Tracking 2 : {} {}", tracking.getMatchedRaws().size(), tracking.isComplete());
    return tracking;
  }

  private void match(Tracking<R, B> tracking, SearchKeyFactory factory) {
    if (tracking.isComplete()) {
      LOG.info(">>>>Tracking complete, skipping match for {}", factory.getClass().getSimpleName());
      return;
    }

    Map<SearchKey, List<B>> baseSearch = new HashMap<>();
    for (B base : tracking.getUnmatchedBases()) {
      var searchKey = factory.apply(base);
      if (!baseSearch.containsKey(searchKey)) {
        baseSearch.put(searchKey, new ArrayList<>());
      }
      LOG.info(">>>>Adding base to search: {} LH:{} TR:{} {}", base.getRuleKey(), base.getLineHash(),
              base.getTextRange()!=null ? base.getTextRange().getHash() : "", base.isResolved());
      baseSearch.get(searchKey).add(base);
    }

    LOG.info("----------------------------Initial {} search keys matched for {}", baseSearch.size(), factory.getClass().getSimpleName());

    for (R raw : tracking.getUnmatchedRaws()) {
      var rawKey = factory.apply(raw);
      LOG.info(">>>>Searching raw for key: {}", rawKey.getClass().getSimpleName()+" rawww "+raw);
      Collection<B> bases = baseSearch.get(rawKey);
      LOG.info(">>>>Printing raw for search: {} LH:{} TR:{} {}", raw.getRuleKey(), raw.getLineHash(),
              raw.getTextRange()!=null ? raw.getTextRange().getHash() : "", raw.isResolved());
      LOG.info("bases found are "+bases+" and base search size is "+baseSearch.size());
      if (bases != null && !bases.isEmpty()) {
        // TODO taking the first one. Could be improved if there are more than 2 issues on the same line.
        // Message could be checked to take the best one.
        var match = bases.iterator().next();
        LOG.info(">----------Matched {} bases for key {}, taking first - {} {} {} {}", bases.size(), rawKey.getClass().getSimpleName(),
                match.getRuleKey(), match.getLineHash(), match.getTextRange()!=null ? match.getTextRange().getHash() : "", match.isResolved());

        LOG.info("trying to match raw and match ===="+raw+" with "+match);
        tracking.match(raw, match);
        baseSearch.get(rawKey).remove(match);
      }
    }

    LOG.info("----------------------------Final {} search keys matched, complete {}", baseSearch.size(), tracking.isComplete());
  }

  private interface SearchKey {
  }

  @FunctionalInterface
  private interface SearchKeyFactory extends Function<Trackable, SearchKey> {
    @Override
    SearchKey apply(Trackable trackable);
  }

  private static class LineAndTextRangeHashKey implements SearchKey {
    private final String ruleKey;
    private final String textRangeHash;
    private final Integer line;

    LineAndTextRangeHashKey(Trackable trackable) {
      this.ruleKey = trackable.getRuleKey();
      this.line = trackable.getLine();
      var textRange = trackable.getTextRange();
      this.textRangeHash = textRange != null ? textRange.getHash() : "";
    }

    // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
    @Override
    public boolean equals(Object o) {
      var that = (LineAndTextRangeHashKey) o;
//      LOG.info(">>> LineAndTextRangeHashKey Comparing {} {} {} to {} {} {}", ruleKey, textRangeHash, line, that.ruleKey, that.textRangeHash, that.line);
      // start with most discriminant field
      return Objects.equals(line, that.line)
        && Objects.equals(textRangeHash, that.textRangeHash)
        && ruleKey.equals(that.ruleKey);
    }

    @Override
    public int hashCode() {
      var result = ruleKey.hashCode();
      result = 31 * result + (textRangeHash != null ? textRangeHash.hashCode() : 0);
      result = 31 * result + (line != null ? line.hashCode() : 0);
      return result;
    }
  }

  private enum LineAndTextRangeHashKeyFactory implements SearchKeyFactory {
    INSTANCE;

    @Override
    public SearchKey apply(Trackable t) {
      return new LineAndTextRangeHashKey(t);
    }
  }

  private static class LineAndLineHashKey implements SearchKey {
    private final String ruleKey;
    private final Integer line;
    private final String lineHash;

    LineAndLineHashKey(Trackable trackable) {
      this.ruleKey = normalizeRule(trackable.getRuleKey());
      this.line = trackable.getLine();
      this.lineHash = trackable.getLineHash();
    }

    // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
    @Override
    public boolean equals(Object o) {
      var that = (LineAndLineHashKey) o;
      // start with most discriminant field
//      LOG.info(">>> LineAndTextRangeHashKey Comparing {} {} {} to {} {} {}", ruleKey, lineHash, line, that.ruleKey, that.lineHash, that.line);
      return Objects.equals(line, that.line)
        && Objects.equals(lineHash, that.lineHash)
        && ruleKey.equals(that.ruleKey);
    }

    @Override
    public int hashCode() {
      var result = ruleKey.hashCode();
      result = 31 * result + (lineHash != null ? lineHash.hashCode() : 0);
      result = 31 * result + (line != null ? line.hashCode() : 0);
      return result;
    }
  }

  private enum LineAndLineHashKeyFactory implements SearchKeyFactory {
    INSTANCE;

    @Override
    public SearchKey apply(Trackable t) {
      return new LineAndLineHashKey(t);
    }
  }

  private static class LineHashKey implements SearchKey {
    private final String ruleKey;
    private final String lineHash;

    LineHashKey(Trackable trackable) {
      this.ruleKey = normalizeRule(trackable.getRuleKey());
      this.lineHash = trackable.getLineHash();
    }

    // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
    @Override
    public boolean equals(Object o) {
      var that = (LineHashKey) o;
      // start with most discriminant field
//      LOG.info(">>> LineHashKey Comparing {} {} to {} {}", ruleKey, lineHash, that.ruleKey, that.lineHash);
      return Objects.equals(lineHash, that.lineHash)
        && ruleKey.equals(that.ruleKey);
    }

    @Override
    public int hashCode() {
      var result = ruleKey.hashCode();
      result = 31 * result + (lineHash != null ? lineHash.hashCode() : 0);
      return result;
    }
  }

  private enum LineHashKeyFactory implements SearchKeyFactory {
    INSTANCE;

    @Override
    public SearchKey apply(Trackable t) {
      return new LineHashKey(t);
    }
  }

  private static class TextRangeHashAndMessageKey implements SearchKey {
    private final String ruleKey;
    private final String message;
    private final String textRangeHash;

    TextRangeHashAndMessageKey(Trackable trackable) {
      this.ruleKey = normalizeRule(trackable.getRuleKey());

      this.message = normMsg(trackable.getMessage()); // normalize
      var textRange = trackable.getTextRange();
      this.textRangeHash = textRange != null ? textRange.getHash() : null;
    }

    // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
    @Override
    public boolean equals(Object o) {
      var that = (TextRangeHashAndMessageKey) o;
//      LOG.info(">>> TextRangeHashAndMessageKey Comparing {} {} {} to {} {} {}", ruleKey, textRangeHash, message, that.ruleKey, that.textRangeHash, that.message);
//      LOG.info(">>> TextRangeHashAndMessageKey Bool {} {} {}", Objects.equals(textRangeHash, that.textRangeHash),
//              message.equals(that.message), ruleKey.equals(that.ruleKey));
      // start with most discriminant field
      return Objects.equals(textRangeHash, that.textRangeHash)
        && message.equals(that.message)
        && ruleKey.equals(that.ruleKey);
    }

    @Override
    public int hashCode() {
      var result = ruleKey.hashCode();
      result = 31 * result + message.hashCode();
      result = 31 * result + (textRangeHash != null ? textRangeHash.hashCode() : 0);
      return result;
    }
  }

  private enum TextRangeHashAndMessageKeyFactory implements SearchKeyFactory {
    INSTANCE;

    @Override
    public SearchKey apply(Trackable t) {
      return new TextRangeHashAndMessageKey(t);
    }
  }

  private static class LineAndMessageKey implements SearchKey {
    private final String ruleKey;
    private final String message;
    private final Integer line;

    LineAndMessageKey(Trackable trackable) {
      this.ruleKey = normalizeRule(trackable.getRuleKey());
      this.message = normMsg(trackable.getMessage()); // normalize
      this.line = trackable.getLine();
    }

    // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
    @Override
    public boolean equals(Object o) {
      var that = (LineAndMessageKey) o;
      // start with most discriminant field
//      LOG.info(">>> LineAndMessageKey Comparing {} {} {} to {} {} {}", ruleKey, message, line, that.ruleKey, that.message, that.line);
//      LOG.info(">>> LineAndMessageKey Bool {} {} {}", Objects.equals(line, that.line), message.equals(that.message), ruleKey.equals(that.ruleKey));
      return Objects.equals(line, that.line)
        && message.equals(that.message)
        && ruleKey.equals(that.ruleKey);
    }

    @Override
    public int hashCode() {
      var result = ruleKey.hashCode();
      result = 31 * result + message.hashCode();
      result = 31 * result + (line != null ? line.hashCode() : 0);
      return result;
    }
  }

  private enum LineAndMessageKeyFactory implements SearchKeyFactory {
    INSTANCE;

    @Override
    public SearchKey apply(Trackable t) {
      return new LineAndMessageKey(t);
    }
  }

  private static class TextRangeHashKey implements SearchKey {
    private final String ruleKey;
    private final String textRangeHash;

    TextRangeHashKey(Trackable trackable) {
      this.ruleKey = normalizeRule(trackable.getRuleKey());
      var textRange = trackable.getTextRange();
      this.textRangeHash = textRange != null ? textRange.getHash() : null;
    }

    // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
    @Override
    public boolean equals(Object o) {
      var that = (TextRangeHashKey) o;
//      LOG.info(">>> TextRangeHashKey Comparing {} {} to {} {}", ruleKey, textRangeHash, that.ruleKey, that.textRangeHash);

      // start with most discriminant field
      return Objects.equals(textRangeHash, that.textRangeHash)
        && ruleKey.equals(that.ruleKey);
    }

    @Override
    public int hashCode() {
      var result = ruleKey.hashCode();
      result = 31 * result + (textRangeHash != null ? textRangeHash.hashCode() : 0);
      return result;
    }
  }


  private enum TextRangeHashKeyFactory implements SearchKeyFactory {
    INSTANCE;

    @Override
    public SearchKey apply(Trackable t) {
      return new TextRangeHashKey(t);
    }
  }

  private static class ServerIssueSearchKey implements SearchKey {
    private final String serverIssueKey;

    ServerIssueSearchKey(Trackable trackable) {
      serverIssueKey = trackable.getServerIssueKey();
    }

    // note: the design of the enclosing caller ensures that 'o' is of the correct class and not null
    @Override
    public boolean equals(Object o) {
      var that = (ServerIssueSearchKey) o;
//      LOG.info(">>> ServerIssueSearchKey Comparing {} to {}", serverIssueKey, that.serverIssueKey);
      return !isBlank(serverIssueKey) && !isBlank(that.serverIssueKey) && serverIssueKey.equals(that.serverIssueKey);
    }

    private static boolean isBlank(String s) {
      return s == null || s.isEmpty();
    }

    @Override
    public int hashCode() {
      return serverIssueKey != null ? serverIssueKey.hashCode() : 0;
    }
  }

  private enum ServerIssueSearchKeyFactory implements SearchKeyFactory {
    INSTANCE;

    @Override
    public SearchKey apply(Trackable trackable) {
      return new ServerIssueSearchKey(trackable);
    }
  }
}
