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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.CheckForNull;
import mediumtest.fixtures.SonarLintBackendFixture;
import mediumtest.fixtures.SonarLintTestRpcServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.analysis.AnalyzeFilesAndTrackParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.analysis.AnalyzeFilesResponse;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.StandaloneRuleConfigDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.rules.UpdateStandaloneRulesConfigurationParams;
import org.sonarsource.sonarlint.core.rpc.protocol.client.log.LogParams;
import org.sonarsource.sonarlint.core.rpc.protocol.common.ClientFileDto;
import org.sonarsource.sonarlint.core.rpc.protocol.common.Language;

import static mediumtest.fixtures.SonarLintBackendFixture.newBackend;
import static mediumtest.fixtures.SonarLintBackendFixture.newFakeClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real CodeScan Salesforce analyzer (plugin key "codescan"), as sent by a CodeScan server, on an Apex class.
 * The analyzer jar is not in this repository. Give its path with -Dcodescan.salesforce.plugin=... or the
 * CODESCAN_SALESFORCE_PLUGIN environment variable; without it these tests are skipped.
 * The analyzer only reports issues when licensed. The licensed test also needs CODESCAN_URL, CODESCAN_ORG and
 * CODESCAN_TOKEN, and the analyzer then asks that server for its license.
 */
class CodeScanSalesforceAnalyzerMediumTests {

  private static final String SCOPE_ID = "codescan-apex";
  private static final String SALESFORCE_RULE_REPOSITORY = "sf:";
  private static final Duration ANALYSIS_TIMEOUT = Duration.ofMinutes(3);
  private static final String APEX_CLASS = "public class GreeterService {\n"
    + "    public String greeting;\n"
    + "\n"
    + "    public String greet(String name) {\n"
    + "        System.debug('Hello ' + name);\n"
    + "        return 'Hello ' + name;\n"
    + "    }\n"
    + "}\n";

  @TempDir
  Path baseDir;

  private SonarLintTestRpcServer backend;
  private SonarLintBackendFixture.FakeSonarLintRpcClient client;

  @AfterEach
  void tearDown() throws ExecutionException, InterruptedException {
    if (backend != null) {
      backend.shutdown().get();
    }
  }

  @Test
  void analyzerLoadsAndAnalysesAnApexClass() throws Exception {
    var analyzerJar = salesforceAnalyzerJar();
    assumeTrue(analyzerJar != null, "Set -Dcodescan.salesforce.plugin or CODESCAN_SALESFORCE_PLUGIN to the analyzer jar");
    var apexFile = startBackendWithApexClass(analyzerJar);

    var analysis = analyze(apexFile, Map.of());

    assertThat(missingClassErrors()).isEmpty();
    assertThat(analysis.join().getFailedAnalysisFiles()).isEmpty();
  }

  @Test
  void licensedAnalyzerFindsIssuesInAnApexClass() throws Exception {
    var analyzerJar = salesforceAnalyzerJar();
    assumeTrue(analyzerJar != null, "Set -Dcodescan.salesforce.plugin or CODESCAN_SALESFORCE_PLUGIN to the analyzer jar");
    var serverUrl = System.getenv("CODESCAN_URL");
    var organization = System.getenv("CODESCAN_ORG");
    var token = System.getenv("CODESCAN_TOKEN");
    assumeTrue(isSet(serverUrl) && isSet(organization) && isSet(token), "Set CODESCAN_URL, CODESCAN_ORG and CODESCAN_TOKEN for the license");
    var apexFile = startBackendWithApexClass(analyzerJar);

    var analysis = analyze(apexFile, Map.of("sonar.host.url", serverUrl, "sonar.organization", organization, "sonar.login", token));

    assertThat(missingClassErrors()).isEmpty();
    assertThat(analysis.join().getFailedAnalysisFiles()).isEmpty();
    await().atMost(ANALYSIS_TIMEOUT).untilAsserted(() -> assertThat(client.getRaisedIssuesForScopeIdAsList(SCOPE_ID)).isNotEmpty());
  }

  private Path startBackendWithApexClass(Path analyzerJar) throws Exception {
    var apexFile = baseDir.resolve("GreeterService.cls");
    Files.writeString(apexFile, APEX_CLASS);
    var fileDto = new ClientFileDto(apexFile.toUri(), baseDir.relativize(apexFile), SCOPE_ID, false, null, apexFile, null, Language.APEX, true);
    client = newFakeClient()
      .withInitialFs(SCOPE_ID, List.of(fileDto))
      .build();
    backend = newBackend()
      .withUnboundConfigScope(SCOPE_ID)
      .withStandaloneEmbeddedPlugin(analyzerJar)
      .withEnabledLanguageInStandaloneMode(Language.APEX)
      .build(client);
    activateAllSalesforceRules();
    return apexFile;
  }

  // The analyzer skips Apex files when no rule of its "sf" repository is active
  private void activateAllSalesforceRules() throws Exception {
    var salesforceRules = backend.getRulesService().listAllStandaloneRulesDefinitions().get().getRulesByKey().keySet().stream()
      .filter(key -> key.startsWith(SALESFORCE_RULE_REPOSITORY))
      .collect(Collectors.toMap(key -> key, key -> new StandaloneRuleConfigDto(true, Map.of())));
    assertThat(salesforceRules).isNotEmpty();
    backend.getRulesService().updateStandaloneRulesConfiguration(new UpdateStandaloneRulesConfigurationParams(salesforceRules));
  }

  private CompletableFuture<AnalyzeFilesResponse> analyze(Path file, Map<String, String> extraProperties) {
    var analysis = backend.getAnalysisService().analyzeFilesAndTrack(
      new AnalyzeFilesAndTrackParams(SCOPE_ID, UUID.randomUUID(), List.of(file.toUri()), extraProperties, true, System.currentTimeMillis()));
    await().atMost(ANALYSIS_TIMEOUT).until(analysis::isDone);
    return analysis;
  }

  private List<String> missingClassErrors() {
    return client.getLogs().stream()
      .map(CodeScanSalesforceAnalyzerMediumTests::messageAndCauses)
      .filter(text -> text.contains("ClassNotFoundException") || text.contains("NoClassDefFoundError")
        || text.contains("NoSuchMethodError") || text.contains("BeanCreationException"))
      .collect(Collectors.toList());
  }

  private static String messageAndCauses(LogParams log) {
    var stackTrace = log.getStackTrace() == null ? Stream.<String>empty() : log.getStackTrace().lines().filter(line -> line.startsWith("Caused by"));
    return Stream.concat(Stream.of(String.valueOf(log.getMessage())), stackTrace).collect(Collectors.joining("\n"));
  }

  @CheckForNull
  private static Path salesforceAnalyzerJar() {
    var location = System.getProperty("codescan.salesforce.plugin", System.getenv("CODESCAN_SALESFORCE_PLUGIN"));
    if (!isSet(location)) {
      return null;
    }
    var jar = Path.of(location);
    return Files.isRegularFile(jar) ? jar : null;
  }

  private static boolean isSet(@CheckForNull String value) {
    return value != null && !value.isBlank();
  }
}
