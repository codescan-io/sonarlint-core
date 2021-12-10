/*
 * SonarLint Core - Implementation
 * Copyright (C) 2016-2021 SonarSource SA
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
package org.sonarsource.sonarlint.core.container.storage;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.commons.io.FilenameUtils;
import org.sonarsource.sonarlint.core.client.api.common.PluginDetails;
import org.sonarsource.sonarlint.core.client.api.common.analysis.AnalysisResults;
import org.sonarsource.sonarlint.core.client.api.common.analysis.IssueListener;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedAnalysisConfiguration;
import org.sonarsource.sonarlint.core.client.api.connected.ProjectBinding;
import org.sonarsource.sonarlint.core.client.api.connected.ProjectStorageStatus;
import org.sonarsource.sonarlint.core.client.api.connected.ServerIssue;
import org.sonarsource.sonarlint.core.commons.Version;
import org.sonarsource.sonarlint.core.commons.http.HttpClient;
import org.sonarsource.sonarlint.core.commons.progress.ProgressMonitor;
import org.sonarsource.sonarlint.core.container.model.DefaultLoadedAnalyzer;
import org.sonarsource.sonarlint.core.container.storage.partialupdate.PartialUpdaterFactory;
import org.sonarsource.sonarlint.core.plugin.commons.PluginInstancesRepository;
import org.sonarsource.sonarlint.core.plugin.commons.pico.ComponentContainer;
import org.sonarsource.sonarlint.core.proto.Sonarlint;
import org.sonarsource.sonarlint.core.serverapi.EndpointParams;
import org.sonarsource.sonarlint.core.storage.ProjectStorage;

public class StorageContainerHandler {
  private final StorageAnalyzer storageAnalyzer;
  private final ProjectStorage projectStorage;
  private final PluginInstancesRepository pluginRepository;
  private final ProjectStorageStatusReader projectStorageStatusReader;
  private final StorageReader storageReader;
  private final StorageFileExclusions storageExclusions;
  private final IssueStoreReader issueStoreReader;
  private final PartialUpdaterFactory partialUpdaterFactory;

  public StorageContainerHandler(StorageAnalyzer storageAnalyzer, ProjectStorage projectStorage,
    PluginInstancesRepository pluginRepository, ProjectStorageStatusReader projectStorageStatusReader,
    StorageReader storageReader, StorageFileExclusions storageExclusions, IssueStoreReader issueStoreReader, PartialUpdaterFactory partialUpdaterFactory) {
    this.storageAnalyzer = storageAnalyzer;
    this.projectStorage = projectStorage;
    this.pluginRepository = pluginRepository;
    this.projectStorageStatusReader = projectStorageStatusReader;
    this.storageReader = storageReader;
    this.storageExclusions = storageExclusions;
    this.issueStoreReader = issueStoreReader;
    this.partialUpdaterFactory = partialUpdaterFactory;
  }

  public AnalysisResults analyze(ComponentContainer container, ConnectedAnalysisConfiguration configuration, IssueListener issueListener,
    ProgressMonitor progress) {
    return storageAnalyzer.analyze(container, configuration, issueListener, progress);
  }

  public Collection<PluginDetails> getPluginDetails() {
    return pluginRepository.getPluginCheckResultByKeys().values().stream().map(p -> new DefaultLoadedAnalyzer(p.getPlugin().getKey(), p.getPlugin().getName(),
      Optional.ofNullable(p.getPlugin().getVersion()).map(Version::toString).orElse(null), p.getSkipReason().orElse(null))).collect(Collectors.toList());
  }

  public ProjectStorageStatus getProjectStorageStatus(String projectKey) {
    return projectStorageStatusReader.apply(projectKey);
  }

  public List<ServerIssue> getServerIssues(ProjectBinding projectBinding, String ideFilePath) {
    return issueStoreReader.getServerIssues(projectBinding, ideFilePath);
  }

  public <G> List<G> getExcludedFiles(ProjectBinding projectBinding, Collection<G> files, Function<G, String> ideFilePathExtractor, Predicate<G> testFilePredicate) {
    return storageExclusions.getExcludedFiles(projectStorage, projectBinding, files, ideFilePathExtractor, testFilePredicate);
  }

  public List<ServerIssue> downloadServerIssues(EndpointParams endpoint, HttpClient client, ProjectBinding projectBinding, String ideFilePath,
    boolean fetchTaintVulnerabilities, ProgressMonitor progress) {
    var updater = partialUpdaterFactory.create(endpoint, client);
    Sonarlint.ProjectConfiguration configuration = storageReader.readProjectConfig(projectBinding.projectKey());
    updater.updateFileIssues(projectBinding, configuration, ideFilePath, fetchTaintVulnerabilities, progress);
    return getServerIssues(projectBinding, ideFilePath);
  }

  public void downloadServerIssues(EndpointParams endpoint, HttpClient client, String projectKey, boolean fetchTaintVulnerabilities, ProgressMonitor progress) {
    var updater = partialUpdaterFactory.create(endpoint, client);
    Sonarlint.ProjectConfiguration configuration = storageReader.readProjectConfig(projectKey);
    updater.updateFileIssues(projectKey, configuration, fetchTaintVulnerabilities, progress);
  }

  public ProjectBinding calculatePathPrefixes(String projectKey, Collection<String> ideFilePaths) {
    List<Path> idePathList = ideFilePaths.stream()
      .map(Paths::get)
      .collect(Collectors.toList());
    List<Path> sqPathList = storageReader.readProjectComponents(projectKey)
      .getComponentList().stream()
      .map(Paths::get)
      .collect(Collectors.toList());

    var fileMatcher = new FileMatcher();
    FileMatcher.Result match = fileMatcher.match(sqPathList, idePathList);
    return new ProjectBinding(projectKey, FilenameUtils.separatorsToUnix(match.sqPrefix().toString()),
      FilenameUtils.separatorsToUnix(match.idePrefix().toString()));

  }
}
