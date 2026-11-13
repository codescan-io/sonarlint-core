/*
 * SonarLint Core - Medium Tests
 * Copyright (C) 2016-2024 SonarSource SA
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
package mediumtest;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import mediumtest.fixtures.SonarLintBackendFixture.FakeSonarLintRpcClient;
import mediumtest.fixtures.SonarLintTestRpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.common.TransientSonarCloudConnectionDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.org.FuzzySearchUserOrganizationsParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.org.GetOrganizationParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.org.ListUserOrganizationsParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.org.OrganizationDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.projects.GetAllProjectsParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.projects.SonarProjectDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.newcode.GetNewCodeDefinitionParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.ClientTrackedFindingDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.LineWithHashDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.ListAllParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.TaintVulnerabilityDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.TextRangeWithHashDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.tracking.TrackWithServerIssuesParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.log.LogLevel;
import org.sonarsource.sonarlint.core.rpc.protocol.client.log.LogParams;
import org.sonarsource.sonarlint.core.rpc.protocol.common.ClientFileDto;
import org.sonarsource.sonarlint.core.rpc.protocol.common.Either;
import org.sonarsource.sonarlint.core.rpc.protocol.common.ImpactSeverity;
import org.sonarsource.sonarlint.core.rpc.protocol.common.Language;
import org.sonarsource.sonarlint.core.rpc.protocol.common.SoftwareQuality;
import org.sonarsource.sonarlint.core.rpc.protocol.common.TokenDto;
import org.sonarsource.sonarlint.core.serverapi.proto.sonarqube.ws.Common;
import org.sonarsource.sonarlint.core.serverapi.proto.sonarqube.ws.Issues;
import org.sonarsource.sonarlint.core.serverconnection.proto.Sonarlint;
import org.sonarsource.sonarlint.core.serverconnection.storage.ProtobufFileUtil;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.util.concurrent.TimeUnit.SECONDS;
import static mediumtest.fixtures.SonarLintBackendFixture.newBackend;
import static mediumtest.fixtures.SonarLintBackendFixture.newFakeClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.sonarsource.sonarlint.core.serverconnection.storage.ProjectStoragePaths.encodeForFs;
import static testutils.TestUtils.protobufBody;

/**
 * CodeScan: runs the core against the answers recorded from a real CodeScan server (staging, organization and project names
 * replaced). The recordings are in src/test/resources/codescan-recordings; each file is one WireMock stub.
 * The connection is a cloud-type ("SonarCloud") connection whose address is the CodeScan server, passed through the
 * alternative cloud environment, exactly as the CodeScan IntelliJ plugin does.
 */
class CodeScanServerMediumTests {

  private static final String CONNECTION_ID = "codescan";
  private static final String ORGANIZATION = "test-org";
  private static final String PROJECT_KEY = "test-project";
  private static final String SCOPE_ID = "codescan-scope";
  // Not a real token. The recordings do not check the token, except 03 which answers only to the word REDACTED.
  private static final String FAKE_TOKEN = "fake-token-for-tests";
  private static final String APEX_FILE = "force-app/main/default/classes/Collection Recipes/AccountNumberOfEmployeesComparator.cls";
  private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(60);

  @RegisterExtension
  static WireMockExtension codescan = WireMockExtension.newInstance()
    .options(wireMockConfig().dynamicPort())
    .build();

  @TempDir
  Path baseDir;

  private SonarLintTestRpcServer backend;

  @BeforeEach
  void loadRecordings() throws IOException, URISyntaxException {
    var recordingsDir = Paths.get(CodeScanServerMediumTests.class.getResource("/codescan-recordings").toURI());
    try (Stream<Path> files = Files.list(recordingsDir)) {
      var jsonFiles = files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().collect(Collectors.toList());
      assertThat(jsonFiles).hasSizeGreaterThan(50);
      for (var file : jsonFiles) {
        codescan.addStubMapping(StubMapping.buildFrom(Files.readString(file, StandardCharsets.UTF_8)));
      }
    }
  }

  @AfterEach
  void tearDown() throws ExecutionException, InterruptedException {
    if (backend != null) {
      backend.shutdown().get();
    }
  }

  @Test
  void bindsToCodeScanOrganization() {
    var client = newFakeClient().withToken(CONNECTION_ID, FAKE_TOKEN).build();
    backend = newBackend()
      .withSonarCloudUrl(codescan.baseUrl())
      .withSonarCloudConnection(CONNECTION_ID, ORGANIZATION, true, null)
      .withBoundConfigScope(SCOPE_ID, CONNECTION_ID, PROJECT_KEY)
      .withFullSynchronization()
      .build(client);

    var organization = backend.getConnectionService()
      .getOrganization(new GetOrganizationParams(Either.forLeft(new TokenDto(FAKE_TOKEN)), ORGANIZATION)).join().getOrganization();
    assertThat(organization.getKey()).isEqualTo(ORGANIZATION);

    var userOrganizations = backend.getConnectionService()
      .listUserOrganizations(new ListUserOrganizationsParams(Either.forLeft(new TokenDto(FAKE_TOKEN)))).join().getUserOrganizations();
    assertThat(userOrganizations).extracting(OrganizationDto::getKey).containsExactlyInAnyOrder("other-org-1", "other-org-2", ORGANIZATION);

    waitForSync(client);
    var serverInfo = ProtobufFileUtil.readFile(connectionStorage().resolve("server_info.pb"), Sonarlint.ServerInfo.parser());
    assertThat(serverInfo.getVersion()).isEqualTo("24.12.0.100206");
    assertThat(client.getLogMessages()).doesNotContain("Error during synchronization");
  }

  @Test
  void nonAdminTokenCanListProjects() {
    backend = newBackend()
      .withSonarCloudUrl(codescan.baseUrl())
      .build();

    var projects = backend.getConnectionService()
      .getAllProjects(new GetAllProjectsParams(new TransientSonarCloudConnectionDto(ORGANIZATION, Either.forLeft(new TokenDto(FAKE_TOKEN)))))
      .join().getSonarProjects();

    assertThat(projects).hasSize(13).extracting(SonarProjectDto::getKey).contains(PROJECT_KEY);
    // the component search any project member may call, not the admin-only api/projects/search
    codescan.verify(getRequestedFor(urlPathEqualTo("/api/components/search.protobuf"))
      .withQueryParam("qualifiers", equalTo("TRK"))
      .withQueryParam("organization", equalTo(ORGANIZATION)));
    codescan.verify(0, getRequestedFor(urlPathEqualTo("/api/projects/search")));
  }

  @Test
  void connectionUsesItsOwnServerAddress() {
    var client = newFakeClient().withToken(CONNECTION_ID, FAKE_TOKEN).build();
    backend = newBackend()
      .withSonarCloudUrl(notCodeScanAddress())
      .withoutSonarCloudWebSockets()
      .withSonarCloudConnectionAtAddress(CONNECTION_ID, ORGANIZATION, codescan.baseUrl())
      .withBoundConfigScope(SCOPE_ID, CONNECTION_ID, PROJECT_KEY)
      .withFullSynchronization()
      .build(client);

    waitForSync(client);

    var serverInfo = ProtobufFileUtil.readFile(connectionStorage().resolve("server_info.pb"), Sonarlint.ServerInfo.parser());
    assertThat(serverInfo.getVersion()).isEqualTo("24.12.0.100206");
    codescan.verify(0, anyRequestedFor(urlPathMatching("/not-codescan/.*")));
  }

  @Test
  void transientConnectionUsesTheGivenServerAddress() {
    backend = newBackend()
      .withSonarCloudUrl(notCodeScanAddress())
      .withoutSonarCloudWebSockets()
      .build();

    var projects = backend.getConnectionService()
      .getAllProjects(new GetAllProjectsParams(new TransientSonarCloudConnectionDto(ORGANIZATION, Either.forLeft(new TokenDto(FAKE_TOKEN)), codescan.baseUrl())))
      .join().getSonarProjects();

    assertThat(projects).hasSize(13);
    codescan.verify(0, anyRequestedFor(urlPathMatching("/not-codescan/.*")));
  }

  @Test
  void organizationLookupUsesTheGivenServerAddress() {
    backend = newBackend()
      .withSonarCloudUrl(notCodeScanAddress())
      .withoutSonarCloudWebSockets()
      .build();
    var connectionService = backend.getConnectionService();

    // the IDE-wide cloud address knows no organizations; that answer must not be reused for the CodeScan address
    var elsewhere = connectionService.fuzzySearchUserOrganizations(new FuzzySearchUserOrganizationsParams(Either.forLeft(new TokenDto(FAKE_TOKEN)), "test"))
      .join().getTopResults();
    assertThat(elsewhere).isEmpty();

    var found = connectionService
      .fuzzySearchUserOrganizations(new FuzzySearchUserOrganizationsParams(Either.forLeft(new TokenDto(FAKE_TOKEN)), "test", codescan.baseUrl()))
      .join().getTopResults();
    assertThat(found).extracting(OrganizationDto::getKey).contains(ORGANIZATION);

    var organization = connectionService
      .getOrganization(new GetOrganizationParams(Either.forLeft(new TokenDto(FAKE_TOKEN)), ORGANIZATION, codescan.baseUrl())).join().getOrganization();
    assertThat(organization.getKey()).isEqualTo(ORGANIZATION);

    var userOrganizations = connectionService
      .listUserOrganizations(new ListUserOrganizationsParams(Either.forLeft(new TokenDto(FAKE_TOKEN)), codescan.baseUrl())).join().getUserOrganizations();
    assertThat(userOrganizations).extracting(OrganizationDto::getKey).containsExactlyInAnyOrder("other-org-1", "other-org-2", ORGANIZATION);
  }

  @Test
  void syncDownloadsActiveRulesAndQualityProfile() {
    var client = newFakeClient().withToken(CONNECTION_ID, FAKE_TOKEN).build();
    backend = newBackend()
      .withSonarCloudUrl(codescan.baseUrl())
      .withSonarCloudConnection(CONNECTION_ID, ORGANIZATION, true, null)
      .withBoundConfigScope(SCOPE_ID, CONNECTION_ID, PROJECT_KEY)
      .withExtraEnabledLanguagesInConnectedMode(Language.APEX)
      .withFullSynchronization()
      .build(client);

    waitForSync(client);

    codescan.verify(getRequestedFor(urlPathEqualTo("/api/qualityprofiles/search.protobuf")).withQueryParam("project", equalTo(PROJECT_KEY)));
    var analyzerConfig = ProtobufFileUtil.readFile(projectStorage().resolve("analyzer_config.pb"), Sonarlint.AnalyzerConfiguration.parser());
    // Apex is language "sf" on CodeScan; its profile is "CodeScan strict way" (recording 22), with 215 active rules (recording 32)
    assertThat(analyzerConfig.getRuleSetsByLanguageKeyMap()).containsKey("sf");
    var apexRules = analyzerConfig.getRuleSetsByLanguageKeyMap().get("sf").getRuleList();
    assertThat(apexRules).hasSize(215);
    assertThat(apexRules).filteredOn(r -> r.getRuleKey().equals("sf:AbstractClassWithoutAbstractMethod"))
      .singleElement()
      .satisfies(r -> assertThat(r.getSeverity()).isEqualTo("MAJOR"));
  }

  @Test
  void unsupportedEndpointsDoNotBreakSync() {
    codescan.stubFor(get(urlPathEqualTo("/codescan-events")).atPriority(1).willReturn(aResponse().withStatus(404)));
    codescan.stubFor(get(urlPathEqualTo("/api/push/sonarlint_events")).atPriority(1).willReturn(aResponse().withStatus(404)));
    var client = newFakeClient().withToken(CONNECTION_ID, FAKE_TOKEN).build();
    backend = newBackend()
      .withSonarCloudUrl(codescan.baseUrl())
      .withSonarCloudWebSocketsUrl("ws://localhost:" + codescan.getPort() + "/codescan-events")
      .withSonarCloudConnectionAndNotifications(CONNECTION_ID, ORGANIZATION, null)
      .withBoundConfigScope(SCOPE_ID, CONNECTION_ID, PROJECT_KEY)
      .withServerSentEventsEnabled()
      .withFullSynchronization()
      .build(client);

    waitForSync(client);

    assertThat(connectionStorage().resolve("server_info.pb")).exists();
    assertThat(client.getLogMessages()).doesNotContain("Error during synchronization");
    // no tight retry loop on the addresses CodeScan does not support
    await().during(30, SECONDS).atMost(40, SECONDS).untilAsserted(() -> {
      assertThat(requestsTo("/codescan-events")).isLessThan(3);
      assertThat(requestsTo("/api/push/sonarlint_events")).isLessThan(3);
    });
  }

  @Test
  void issuesFromServerKeepCodeScanSeverities() throws IOException, URISyntaxException {
    // Recording 45 only has LOW and MEDIUM impacts. Same answer, with the first two issues moved to BLOCKER and INFO,
    // the two impact severities CodeScan has and SonarQube 10.x did not.
    var recorded = Issues.SearchWsResponse.parseFrom(recordedBody("45-issues-vulns.json"));
    var blockerIssue = recorded.getIssues(0);
    var infoIssue = recorded.getIssues(1);
    var changed = recorded.toBuilder()
      .setIssues(0, blockerIssue.toBuilder().clearImpacts().addImpacts(impact(Common.ImpactSeverity.ImpactSeverity_BLOCKER)))
      .setIssues(1, infoIssue.toBuilder().clearImpacts().addImpacts(impact(Common.ImpactSeverity.ImpactSeverity_INFO)))
      .build();
    codescan.stubFor(get(urlPathEqualTo("/api/issues/search.protobuf")).atPriority(1)
      .withQueryParam("types", equalTo("VULNERABILITY"))
      .withQueryParam("componentKeys", equalTo(PROJECT_KEY))
      .willReturn(aResponse().withStatus(200).withResponseBody(protobufBody(changed))));
    var client = newFakeClient()
      .withToken(CONNECTION_ID, FAKE_TOKEN)
      .withInitialFs(SCOPE_ID, baseDir, List.of(clientFile(componentPath(blockerIssue)), clientFile(componentPath(infoIssue))))
      .build();
    backend = newBackend()
      .withSonarCloudUrl(codescan.baseUrl())
      .withSonarCloudConnection(CONNECTION_ID, ORGANIZATION, true, null)
      .withBoundConfigScope(SCOPE_ID, CONNECTION_ID, PROJECT_KEY)
      // taint vulnerabilities are downloaded only when a taint-capable language is on
      .withExtraEnabledLanguagesInConnectedMode(Language.JAVA)
      .withFullSynchronization()
      .build(client);

    waitForSync(client);

    var taints = backend.getTaintVulnerabilityTrackingService().listAll(new ListAllParams(SCOPE_ID)).join().getTaintVulnerabilities();
    assertThat(taints).hasSize(recorded.getIssuesCount());
    assertThat(impactsOf(taints, blockerIssue.getKey())).containsExactly(SoftwareQuality.SECURITY + "=" + ImpactSeverity.BLOCKER);
    assertThat(impactsOf(taints, infoIssue.getKey())).containsExactly(SoftwareQuality.SECURITY + "=" + ImpactSeverity.INFO);
  }

  @Test
  void batchIssuesHtmlAnswerDoesNotBreakSync() {
    var client = newFakeClient()
      .withToken(CONNECTION_ID, FAKE_TOKEN)
      .withInitialFs(SCOPE_ID, baseDir, List.of(clientFile(APEX_FILE)))
      .build();
    backend = newBackend()
      .withSonarCloudUrl(codescan.baseUrl())
      .withSonarCloudConnection(CONNECTION_ID, ORGANIZATION, true, null)
      .withBoundConfigScope(SCOPE_ID, CONNECTION_ID, PROJECT_KEY)
      .withFullSynchronization()
      .build(client);
    waitForSync(client);
    var finding = new ClientTrackedFindingDto(UUID.randomUUID(), null, new TextRangeWithHashDto(1, 0, 1, 10, "hash"),
      new LineWithHashDto(1, "linehash"), "sf:AvoidPublicFields", "message");

    // recording 55: CodeScan answers /batch/issues with 200 and an HTML page, not protobuf
    var response = backend.getIssueTrackingService()
      .trackWithServerIssues(new TrackWithServerIssuesParams(SCOPE_ID, Map.of(Path.of(APEX_FILE), List.of(finding)), true));

    assertThat(response).succeedsWithin(Duration.ofSeconds(20))
      .satisfies(result -> assertThat(result.getIssuesByIdeRelativePath().get(Path.of(APEX_FILE)))
        .singleElement()
        .satisfies(issue -> assertThat(issue.isRight()).as("tracked as a local-only issue").isTrue()));
    assertThat(requestsTo("/batch/issues")).isEqualTo(1);
    assertThat(client.getLogs()).filteredOn(log -> log.getLevel() == LogLevel.WARN)
      .extracting(LogParams::getMessage)
      .anySatisfy(message -> assertThat(message).contains("/batch/issues"));
  }

  @Test
  void newCodePeriod400IsHandled() {
    var client = newFakeClient().withToken(CONNECTION_ID, FAKE_TOKEN).build();
    backend = newBackend()
      .withSonarCloudUrl(codescan.baseUrl())
      .withSonarCloudConnection(CONNECTION_ID, ORGANIZATION, true, null)
      .withBoundConfigScope(SCOPE_ID, CONNECTION_ID, PROJECT_KEY)
      .withFullSynchronization()
      .build(client);

    waitForSync(client);

    // recording 43: PREVIOUS_VERSION with a date and no version, so the text is "Since <date>"
    var newCode = backend.getNewCodeService().getNewCodeDefinition(new GetNewCodeDefinitionParams(SCOPE_ID)).join();
    assertThat(newCode.isSupported()).isTrue();
    assertThat(newCode.getDescription()).startsWith("Since ");
    assertThat(client.getLogMessages()).doesNotContain("Error while fetching new code definition");
    codescan.verify(getRequestedFor(urlPathEqualTo("/api/measures/component.protobuf")).withQueryParam("additionalFields", equalTo("period")));
    // recording 42: CodeScan answers 400 to "periods"
    codescan.verify(0, getRequestedFor(urlPathEqualTo("/api/measures/component.protobuf")).withQueryParam("additionalFields", equalTo("periods")));
  }

  @Test
  void websocketOffForCodeScanEnvironment() {
    var client = newFakeClient().withToken(CONNECTION_ID, FAKE_TOKEN).build();
    backend = newBackend()
      .withSonarCloudUrl(codescan.baseUrl())
      // as the CodeScan plugin starts the core: a CodeScan address and no WebSocket address
      .withoutSonarCloudWebSockets()
      .withSonarCloudConnectionAndNotifications(CONNECTION_ID, ORGANIZATION, null)
      .withBoundConfigScope(SCOPE_ID, CONNECTION_ID, PROJECT_KEY)
      .withServerSentEventsEnabled()
      .withFullSynchronization()
      .build(client);

    waitForSync(client);

    await().during(2, SECONDS).atMost(5, SECONDS).untilAsserted(() -> assertThat(client.getLogMessages())
      .noneMatch(message -> message.contains("websocket connection")));
  }

  /**
   * An address on the same recording server with no recordings: anything sent there gets 404.
   */
  private static String notCodeScanAddress() {
    return codescan.baseUrl() + "/not-codescan";
  }

  private static void waitForSync(FakeSonarLintRpcClient client) {
    verify(client, timeout(SYNC_TIMEOUT.toMillis())).didSynchronizeConfigurationScopes(any());
  }

  private Path connectionStorage() {
    return backend.getStorageRoot().resolve(encodeForFs(CONNECTION_ID));
  }

  private Path projectStorage() {
    return connectionStorage().resolve("projects").resolve(encodeForFs(PROJECT_KEY));
  }

  private static int requestsTo(String path) {
    return codescan.findAll(getRequestedFor(urlPathEqualTo(path))).size();
  }

  private static byte[] recordedBody(String recordingFileName) throws IOException, URISyntaxException {
    var file = Paths.get(CodeScanServerMediumTests.class.getResource("/codescan-recordings/" + recordingFileName).toURI());
    return StubMapping.buildFrom(Files.readString(file, StandardCharsets.UTF_8)).getResponse().getByteBody();
  }

  private static Common.Impact impact(Common.ImpactSeverity severity) {
    return Common.Impact.newBuilder().setSoftwareQuality(Common.SoftwareQuality.SECURITY).setSeverity(severity).build();
  }

  private static String componentPath(Issues.Issue issue) {
    return issue.getComponent().substring((PROJECT_KEY + ":").length());
  }

  private ClientFileDto clientFile(String relativePath) {
    var fsPath = baseDir.resolve(relativePath);
    return new ClientFileDto(fsPath.toUri(), Path.of(relativePath), SCOPE_ID, false, null, fsPath, null, null, true);
  }

  private static List<String> impactsOf(List<TaintVulnerabilityDto> taints, String serverKey) {
    var taint = taints.stream().filter(t -> t.getSonarServerKey().equals(serverKey)).findFirst().orElseThrow();
    assertThat(taint.getSeverityMode().isRight()).as("cloud connections show impacts (MQR mode)").isTrue();
    return taint.getSeverityMode().getRight().getImpacts().stream()
      .map(impact -> impact.getSoftwareQuality() + "=" + impact.getImpactSeverity())
      .collect(Collectors.toList());
  }
}
