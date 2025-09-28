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

    public Collection<Trackable> apply(Collection<Trackable> baseIssues, Collection<Trackable> nextIssues,
            boolean inheritSeverity) {
        baseIssues.forEach(
                i -> LOG.info(">>>>Base {} {} {} {} {} {}", i.getRuleKey(), i.getServerIssueKey(), i.getLineHash(),
                        i.isResolved(), i.getCreationDate(),
                        i.getTextRange() != null ? i.getTextRange().getHash() : ""));
        nextIssues.forEach(
                i -> LOG.info(">>>>Next {} {} {} {} {} {}", i.getRuleKey(), i.getServerIssueKey(), i.getLineHash(),
                        i.isResolved(), i.getCreationDate(),
                        i.getTextRange() != null ? i.getTextRange().getHash() : ""));
        LOG.info("Base issues size " + baseIssues + " and next issues size " + nextIssues);
        Collection<Trackable> trackedIssues = new ArrayList<>();

        LOG.info("calling tracker with next issues " + nextIssues + " and base issues " + baseIssues);
        var tracking = new Tracker<>().track(() -> nextIssues, () -> baseIssues);


        tracking.getMatchedRaws().forEach((raw, base) -> {
            LOG.info("raw and base server keys are "+raw.getServerIssueKey()+" and "+base.getServerIssueKey());
            LOG.info("raw and base is resolved "+raw.isResolved()+" and "+base.isResolved());
            boolean serverResolved =
                    (base.getServerIssueKey() != null && base.isResolved())
                            || (raw.getServerIssueKey()  != null && raw.isResolved());

            LOG.info("Matched raw {} {} {} with base {} {} {}, serverResolved={}",
                    raw.getRuleKey(), raw.getLineHash(), raw.isResolved(),
                    base.getRuleKey(), base.getLineHash(), base.isResolved(), serverResolved);

            if (serverResolved) {
                LOG.info("Muted by server FP/WONTFIX: key={} rule={} LH={}",
                        base.getServerIssueKey() != null ? base.getServerIssueKey() : raw.getServerIssueKey(),
                        base.getServerIssueKey() != null ? base.getRuleKey()        : raw.getRuleKey(),
                        base.getServerIssueKey() != null ? base.getLineHash()       : raw.getLineHash());
            } else {
                trackedIssues.add(new CombinedTrackable(base, raw, inheritSeverity));
            }
        });
        var resolvedByLH   = new java.util.HashSet<String>();
        var resolvedByLine = new java.util.HashSet<String>();
        for (Trackable b : baseIssues) {
            LOG.info("Base issue in loop {} {} {}", b.getRuleKey(), b.getLineHash(), b.isResolved());
            if (!b.isResolved()) continue;                       // only FP/WF (or fixed/removed if you set that)
            String rk = normalizeRule(b.getRuleKey());           // helper below
            if (rk == null) continue;
            if (b.getLineHash() != null && !b.getLineHash().isEmpty()) {
                resolvedByLH.add(rk + "#" + b.getLineHash());
            }
            if (b.getLine() != null) {
                LOG.info("Adding resolved by line {}#{}", rk, b.getLine());
                resolvedByLine.add(rk + "#" + b.getLine());
            }
        }

    LOG.info("issues tracked are "+trackedIssues);

        for (Trackable next : tracking.getUnmatchedRaws()) {
            LOG.info("Unmatched raw before suppression {} {} {}", next.getRuleKey(), next.getLineHash(), next.isResolved());
            String rk = normalizeRule(next.getRuleKey());
            String lh = next.getLineHash();
            Integer ln = next.getLine();

            boolean suppressed =
                    (rk != null && lh != null && resolvedByLH.contains(rk + "#" + lh))
                            || (rk != null && ln != null && resolvedByLine.contains(rk + "#" + ln));

            if (suppressed) {
                LOG.info("Muted unmatched raw by server FP/WF: rule={} LH={} line={}", rk, lh, ln);
                continue; // do not add
            }

            LOG.info("Unmatched raw {} {} {}", next.getRuleKey(), next.getLineHash(), next.isResolved());
            if (next.getServerIssueKey() != null) {
                LOG.info("Disconnected {} {}", next.getRuleKey(), next.isResolved());
                next = new DisconnectedTrackable(next);
            } else if (next.getCreationDate() == null) {
                LOG.info("Leaked {} {}", next.getRuleKey(), next.isResolved());
                next = new LeakedTrackable(next);
            }
            trackedIssues.add(next);
        }


    LOG.info("tracked size "+trackedIssues.size());
    trackedIssues.forEach(i -> LOG.info("Tracked {} {} {}", i.getRuleKey(), i.getLine(), i.isResolved()));

    return trackedIssues;
  }
    // Put this tiny helper inside IssueTracker (same class)
    private static String normalizeRule(String rk) {
        LOG.info("Normalizing rule key in issuetracker 120===: " + rk);
        return rk == null ? null : (rk.startsWith(":") ? rk.substring(1) : rk);
    }
}