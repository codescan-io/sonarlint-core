/*
 * SonarLint Core - Server Connection
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
package org.sonarsource.sonarlint.core.serverconnection;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.commons.log.LogOutput.Level;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogTester;
import org.sonarsource.sonarlint.core.serverconnection.storage.PluginsStorage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CodeScan: on a first sync the storage files do not exist yet. That is expected,
 * so it is a warning. Any other read failure (e.g. a broken file) stays an error.
 */
class StorageReadLogLevelTests {
  @RegisterExtension
  private static final SonarLintLogTester logTester = new SonarLintLogTester();

  @Test
  void missing_analyzer_configuration_is_a_warning(@TempDir Path projectRoot) {
    assertThat(new AnalyzerConfigurationStorage(projectRoot).isValid()).isFalse();

    assertThat(logTester.logs(Level.ERROR)).isEmpty();
    assertThat(logTester.logs(Level.WARN)).anyMatch(l -> l.startsWith("Could not load analyzer configuration storage"));
  }

  @Test
  void broken_analyzer_configuration_is_still_an_error(@TempDir Path projectRoot) throws Exception {
    Files.write(projectRoot.resolve("analyzer_config.pb"), new byte[] {(byte) 0xFF, 0x01, 0x02});

    assertThat(new AnalyzerConfigurationStorage(projectRoot).isValid()).isFalse();

    assertThat(logTester.logs(Level.ERROR)).anyMatch(l -> l.startsWith("Could not load analyzer configuration storage"));
  }

  @Test
  void missing_plugins_storage_is_a_warning(@TempDir Path connectionRoot) {
    assertThat(new PluginsStorage(connectionRoot).isValid()).isFalse();

    assertThat(logTester.logs(Level.ERROR)).isEmpty();
    assertThat(logTester.logs(Level.WARN)).anyMatch(l -> l.startsWith("Could not load plugins storage"));
  }

  @Test
  void broken_plugins_storage_is_still_an_error(@TempDir Path connectionRoot) throws Exception {
    Files.createDirectories(connectionRoot.resolve("plugins"));
    Files.write(connectionRoot.resolve("plugins").resolve(PluginsStorage.PLUGIN_REFERENCES_PB), new byte[] {(byte) 0xFF, 0x01, 0x02});

    assertThat(new PluginsStorage(connectionRoot).isValid()).isFalse();

    assertThat(logTester.logs(Level.ERROR)).anyMatch(l -> l.startsWith("Could not load plugins storage"));
  }
}
