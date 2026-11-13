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

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.serverapi.plugins.ServerPlugin;

import static org.assertj.core.api.Assertions.assertThat;

class StoredPluginTests {

  private final StoredPlugin storedPlugin = new StoredPlugin("sf", "0123abcd", Path.of("sf.jar"));

  @Test
  void same_hash_is_the_same() {
    assertThat(storedPlugin.hasSameHash(new ServerPlugin("sf", "0123abcd", "sf.jar", true))).isTrue();
  }

  @Test
  void different_hash_of_the_same_length_is_not_the_same() {
    assertThat(storedPlugin.hasSameHash(new ServerPlugin("sf", "0123abce", "sf.jar", true))).isFalse();
  }

  @Test
  void different_hash_of_another_length_is_not_the_same() {
    assertThat(storedPlugin.hasSameHash(new ServerPlugin("sf", "0123abcd00", "sf.jar", true))).isFalse();
  }

  @Test
  void missing_server_hash_is_not_the_same() {
    assertThat(storedPlugin.hasSameHash(new ServerPlugin("sf", null, "sf.jar", true))).isFalse();
  }

}
