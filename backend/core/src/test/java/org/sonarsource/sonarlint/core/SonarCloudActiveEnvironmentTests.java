/*
 * SonarLint Core - Implementation
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
package org.sonarsource.sonarlint.core;

import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.sonarsource.sonarlint.core.commons.log.LogOutput;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CodeScan: the cloud address used when the IDE passes no cloud environment at start-up.
 */
class SonarCloudActiveEnvironmentTests {

  @RegisterExtension
  private static final SonarLintLogTester logTester = new SonarLintLogTester();

  private static final String PROPERTY = "sonarlint.internal.sonarcloud.url";

  private String savedProperty;

  @BeforeEach
  void saveProperty() {
    savedProperty = System.getProperty(PROPERTY);
    System.clearProperty(PROPERTY);
  }

  @AfterEach
  void restoreProperty() {
    if (savedProperty == null) {
      System.clearProperty(PROPERTY);
    } else {
      System.setProperty(PROPERTY, savedProperty);
    }
  }

  @Test
  void codescan_default_is_app_codescan_io_with_no_websocket() {
    var environment = SonarCloudActiveEnvironment.codeScanDefault();

    assertThat(environment.getUri()).isEqualTo(URI.create("https://app.codescan.io"));
    assertThat(environment.getWebSocketsEndpointUri()).isNull();
  }

  @Test
  void codescan_default_reads_the_address_property() {
    System.setProperty(PROPERTY, "https://staging.codescan.io");

    var environment = SonarCloudActiveEnvironment.codeScanDefault();

    assertThat(environment.getUri()).isEqualTo(URI.create("https://staging.codescan.io"));
    assertThat(environment.getWebSocketsEndpointUri()).isNull();
  }

  @Test
  void codescan_default_ignores_a_malformed_address_property() {
    System.setProperty(PROPERTY, "staging codescan io");

    var environment = SonarCloudActiveEnvironment.codeScanDefault();

    assertThat(environment.getUri()).isEqualTo(URI.create("https://app.codescan.io"));
    assertThat(logTester.logs(LogOutput.Level.WARN))
      .containsExactly("Ignoring system property 'sonarlint.internal.sonarcloud.url': not an absolute address. Using https://app.codescan.io");
  }
}
