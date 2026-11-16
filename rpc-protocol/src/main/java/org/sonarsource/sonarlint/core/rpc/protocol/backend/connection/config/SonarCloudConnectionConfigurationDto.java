/*
 * SonarLint Core - RPC Protocol
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
package org.sonarsource.sonarlint.core.rpc.protocol.backend.connection.config;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

public class SonarCloudConnectionConfigurationDto {

  /**
   * The id of the connection on the client side
   */
  private final String connectionId;
  private final String organization;
  private final boolean disableNotifications;
  // CodeScan: the server address of this connection; when empty, the cloud address given at start-up is used
  private final String serverUrl;

  public SonarCloudConnectionConfigurationDto(String connectionId, String organization, boolean disableNotifications) {
    this(connectionId, organization, disableNotifications, null);
  }

  public SonarCloudConnectionConfigurationDto(String connectionId, String organization, boolean disableNotifications, @Nullable String serverUrl) {
    this.connectionId = connectionId;
    this.organization = organization;
    this.disableNotifications = disableNotifications;
    this.serverUrl = serverUrl;
  }

  public String getConnectionId() {
    return connectionId;
  }

  public String getOrganization() {
    return organization;
  }

  public boolean isDisableNotifications() {
    return disableNotifications;
  }

  @CheckForNull
  public String getServerUrl() {
    return serverUrl;
  }
}
