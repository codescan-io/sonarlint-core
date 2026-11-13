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
package org.sonarsource.sonarlint.core.spring;

import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.InitializeParams;
import org.sonarsource.sonarlint.core.rpc.protocol.backend.initialize.SonarCloudAlternativeEnvironmentDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SonarLintSpringAppConfigTests {

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
  void without_a_cloud_environment_from_the_ide_the_cloud_address_is_codescan_and_there_is_no_websocket() {
    var params = mock(InitializeParams.class);
    when(params.getAlternativeSonarCloudEnvironment()).thenReturn(null);

    var environment = new SonarLintSpringAppConfig().provideSonarCloudActiveEnvironment(params);

    assertThat(environment.getUri()).isEqualTo(URI.create("https://app.codescan.io"));
    assertThat(environment.getWebSocketsEndpointUri()).isNull();
  }

  @Test
  void the_cloud_environment_from_the_ide_wins() {
    var params = mock(InitializeParams.class);
    when(params.getAlternativeSonarCloudEnvironment())
      .thenReturn(new SonarCloudAlternativeEnvironmentDto(URI.create("https://codescan.example.com"), null));

    var environment = new SonarLintSpringAppConfig().provideSonarCloudActiveEnvironment(params);

    assertThat(environment.getUri()).isEqualTo(URI.create("https://codescan.example.com"));
    assertThat(environment.getWebSocketsEndpointUri()).isNull();
  }
}
