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
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;

public class IssueTracker {

  /**
   * Local issue tracking: baseIssues are existing issue, nextIssues are raw issues coming from the analysis.
   * Server issue tracking: baseIssues are server issues, nextIssues are the existing issue, coming from local issue tracking.
   */
  private static final SonarLintLogger LOG = SonarLintLogger.get();

  public Collection<Trackable> apply(Collection<Trackable> baseIssues, Collection<Trackable> nextIssues, boolean inheritSeverity) {
    baseIssues.forEach(i -> LOG.info(">>>>Base {} {} {} {} {} {}", i.getRuleKey(), i.getServerIssueKey(), i.getLineHash(), i.isResolved(), i.getCreationDate(), i.getTextRange()!= null ? i.getTextRange().getHash() : ""));
    nextIssues.forEach(i -> LOG.info(">>>>Next {} {} {} {} {} {}", i.getRuleKey(), i.getServerIssueKey(), i.getLineHash(), i.isResolved(), i.getCreationDate(), i.getTextRange()!= null ? i.getTextRange().getHash() : ""));

    Collection<Trackable> trackedIssues = new ArrayList<>();
    System.out.println("issues tracked are "+trackedIssues);
    var tracking = new Tracker<>().track(() -> nextIssues, () -> baseIssues);

    tracking.getMatchedRaws().entrySet().stream()
            .map(e -> new CombinedTrackable(e.getValue(), e.getKey(), inheritSeverity))
            .forEach(trackedIssues::add);

    for (Trackable next : tracking.getUnmatchedRaws()) {
      System.out.println("tracked "+next);
      if (next.getServerIssueKey() != null) {
        LOG.info("Disconnected {} {}", next.getRuleKey(), next.isResolved());
        // not matched with server anymore
        next = new DisconnectedTrackable(next);
      } else if (next.getCreationDate() == null) {
        // first time we see this issue locally
        LOG.info("Leaked {} {}", next.getRuleKey(), next.isResolved());
        next = new LeakedTrackable(next);
      }
      trackedIssues.add(next);
    }

    trackedIssues.forEach(i -> LOG.info("Tracked {} {} {}", i.getRuleKey(), i.getLine(), i.isResolved()));

    return trackedIssues;
  }
}