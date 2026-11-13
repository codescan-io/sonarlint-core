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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import mediumtest.fixtures.SonarLintTestRpcServer;
import mediumtest.fixtures.TestPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.analysis.AnalyzeFilesParams;
import org.sonarsource.sonarlint.core.rpc.protocol.common.ClientFileDto;

import static mediumtest.fixtures.SonarLintBackendFixture.newBackend;
import static mediumtest.fixtures.SonarLintBackendFixture.newFakeClient;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CodeScan: the IDE passes the user's token to the analysis as sonar.login. The backend writes the analysis
 * settings to the log on every analysis, so the token must not appear there.
 */
class CodeScanAnalysisLogMediumTests {

  private static final String CONFIG_SCOPE_ID = "CONFIG_SCOPE_ID";
  private static final String FAKE_TOKEN = "fake-token-for-tests";
  private SonarLintTestRpcServer backend;

  @AfterEach
  void stopBackend() throws ExecutionException, InterruptedException {
    if (backend != null) {
      backend.shutdown().get();
    }
  }

  @Test
  void analysis_log_does_not_show_the_token(@TempDir Path baseDir) throws IOException {
    var filePath = baseDir.resolve("pom.xml");
    Files.writeString(filePath, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project>\n</project>");
    var fileUri = filePath.toUri();
    var client = newFakeClient()
      .withInitialFs(CONFIG_SCOPE_ID, baseDir, List.of(new ClientFileDto(fileUri, baseDir.relativize(filePath), CONFIG_SCOPE_ID, false, null, filePath, null, null, true)))
      .build();
    backend = newBackend()
      .withUnboundConfigScope(CONFIG_SCOPE_ID)
      .withStandaloneEmbeddedPluginAndEnabledLanguage(TestPlugin.XML)
      .build(client);

    backend.getAnalysisService().analyzeFiles(new AnalyzeFilesParams(CONFIG_SCOPE_ID, UUID.randomUUID(), List.of(fileUri),
      Map.of("sonar.login", FAKE_TOKEN, "sonar.password", "", "sonar.host.url", "https://app.codescan.io"), System.currentTimeMillis())).join();

    // Make sure the settings line was written, so the check below cannot pass just because nothing was logged.
    assertThat(client.getLogMessages())
      .anySatisfy(message -> assertThat(message).contains("Triggering analysis with configuration").contains("sonar.login=******"));
    assertThat(client.getLogMessages()).noneSatisfy(message -> assertThat(message).contains(FAKE_TOKEN));
  }

}
