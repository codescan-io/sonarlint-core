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
package mediumtest.analysis;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import mediumtest.fixtures.SonarLintTestRpcServer;
import mediumtest.fixtures.TestPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.analysis.ActiveRuleDto;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.analysis.GetAnalysisConfigParams;

import static mediumtest.fixtures.SonarLintBackendFixture.newBackend;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * CodeScan: active rules keep the internal key from the analyzer's rule definition (row C6).
 * The Java test analyzer gives java:NoSonar the internal key "S1291" and java:S106 none.
 */
class CodeScanRuleInternalKeyMediumTests {

  private static final String CONFIG_SCOPE_ID = "config scope id";
  private static final String CONNECTION_ID = "myConnection";
  private static final String PROJECT_KEY = "sample-project";
  private SonarLintTestRpcServer backend;

  @AfterEach
  void stopBackend() throws ExecutionException, InterruptedException {
    if (backend != null) {
      backend.shutdown().get();
    }
  }

  @Test
  void connected_mode_active_rules_carry_the_analyzer_internal_key() throws Exception {
    backend = newBackend()
      .withConnectedEmbeddedPluginAndEnabledLanguage(TestPlugin.JAVA)
      .withSonarQubeConnection(CONNECTION_ID,
        storage -> storage.withServerVersion("9.9").withProject(PROJECT_KEY, project -> project.withRuleSet("java", ruleSet -> ruleSet
          .withActiveRule("java:NoSonar", "MAJOR")
          .withActiveRule("java:S106", "MAJOR"))))
      .withBoundConfigScope(CONFIG_SCOPE_ID, CONNECTION_ID, PROJECT_KEY)
      .build();

    var activeRules = backend.getAnalysisService().getAnalysisConfig(new GetAnalysisConfigParams(CONFIG_SCOPE_ID)).get().getActiveRules();

    assertThat(activeRules)
      .extracting(ActiveRuleDto::getRuleKey, ActiveRuleDto::getInternalKey)
      .containsExactlyInAnyOrder(tuple("java:NoSonar", "S1291"), tuple("java:S106", null));
  }

  @Test
  void standalone_active_rules_carry_the_analyzer_internal_key() throws Exception {
    backend = newBackend()
      .withStandaloneEmbeddedPluginAndEnabledLanguage(TestPlugin.JAVA)
      .withStandaloneRuleConfig("java:NoSonar", true, Map.of())
      .withUnboundConfigScope(CONFIG_SCOPE_ID)
      .build();

    var activeRules = backend.getAnalysisService().getAnalysisConfig(new GetAnalysisConfigParams(CONFIG_SCOPE_ID)).get().getActiveRules();

    assertThat(activeRules)
      .filteredOn(rule -> rule.getRuleKey().equals("java:NoSonar"))
      .extracting(ActiveRuleDto::getInternalKey)
      .containsExactly("S1291");
  }

}
