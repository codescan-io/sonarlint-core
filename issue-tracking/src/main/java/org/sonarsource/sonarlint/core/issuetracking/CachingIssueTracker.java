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

import java.util.Collection;
import java.util.Collections;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;

public class CachingIssueTracker extends IssueTracker {

  private final IssueTrackerCache cache;
  private static final SonarLintLogger LOG = SonarLintLogger.get();


  public CachingIssueTracker(IssueTrackerCache cache) {
    this.cache = cache;
  }

  /**
   * Match a new set of trackables to current state.
   * If this is the first analysis, leave creation date as null.
   *
   * @param file the file analyzed
   * @param trackables the trackables in the file
   */
  public synchronized Collection<Trackable> matchAndTrackAsNew(String file, Collection<Trackable> trackables) {
    LOG.info("In matchAndTrackAsNew");
    Collection<Trackable> tracked;
    if (cache.isFirstAnalysis(file)) {
      LOG.info("Creating null first analysis");
      tracked = trackables;
    } else {
      tracked = apply(cache.getCurrentTrackables(file), trackables, false);
    }
    cache.put(file, tracked);
    LOG.info("Tracked size in cache: " + cache+" and tracked ++ "+tracked);
    return tracked;
  }

  /**
   * "Rebase" current trackables against given trackables.
   *
   * @param file the file analyzed
   * @param trackables the trackables in the file
   */
  public synchronized Collection<Trackable> matchAndTrackAsBase(String file, Collection<Trackable> trackables) {
    // store issues (ProtobufIssueTrackable) are of no use since they can't be used in markers. There should have been
    // an analysis before that set the live issues for the file (even if it is empty)
    LOG.info("In matchAndTrackAsBase");
    Collection<Trackable> current = cache.getLiveOrFail(file);
    if (current.isEmpty()) {
      return Collections.emptyList();
    }
    LOG.info("Trackable {} and Current {}", trackables, current);
    LOG.info("Current size: " + current.size());
    LOG.info("Trackable size: " + trackables.size());
    var tracked = apply(trackables, current, true);
    LOG.info("cache in matchAndTrack as base "+cache);
    cache.put(file, tracked);
    LOG.info("cache in matchAndTrack as base "+cache+" also tracked "+tracked);

    return tracked;
  }

  public void clear() {
    LOG.info("Clearing cache ===== "+cache);
    cache.clear();
  }

  public void shutdown() {
    cache.shutdown();
  }

}
