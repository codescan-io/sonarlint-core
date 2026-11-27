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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mediumtest.fixtures.SonarLintTestRpcServer;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.analysis.AnalyzeFilesAndTrackParams;

import static mediumtest.fixtures.SonarLintBackendFixture.newBackend;
import static mediumtest.fixtures.SonarLintBackendFixture.newFakeClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The harness prints every RPC message. Credentials passed as analysis properties must never show up in that printout.
 * Uses fake values only; no server is contacted and no analyzer jar is needed.
 */
class RpcTraceSecretsMediumTests {

  private static final String FAKE_TOKEN = "fake-token-ab12cd";

  @Test
  void credentialsInAnalysisPropertiesAreNotPrinted() throws Exception {
    var captured = new ByteArrayOutputStream();
    var originalOut = System.out;
    SonarLintTestRpcServer backend = null;
    try {
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      var client = newFakeClient().build();
      backend = newBackend().withUnboundConfigScope("scope").build(client);
      var analysis = backend.getAnalysisService().analyzeFilesAndTrack(new AnalyzeFilesAndTrackParams("scope", UUID.randomUUID(),
        List.of(URI.create("file:///nowhere/Fake.cls")),
        Map.of("sonar.host.url", "https://fake.invalid", "sonar.login", FAKE_TOKEN, "sonar.password", FAKE_TOKEN, "sonar.token", FAKE_TOKEN), true,
        System.currentTimeMillis()));
      await().until(analysis::isDone);
    } finally {
      System.setOut(originalOut);
      if (backend != null) {
        backend.shutdown().get();
      }
    }

    var printed = captured.toString(StandardCharsets.UTF_8);
    assertThat(printed).contains("analyzeFilesAndTrack").contains("https://fake.invalid");
    assertThat(printed).doesNotContain(FAKE_TOKEN);
  }
}
