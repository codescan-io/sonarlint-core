/*
 * SonarLint Core - Implementation
 * Copyright (C) 2016-2023 SonarSource SA
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
package org.sonarsource.sonarlint.core.repository.connection;

import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.sonarsource.sonarlint.core.commons.ConnectionKind;
import org.sonarsource.sonarlint.core.serverapi.EndpointParams;

public class SonarCloudConnectionConfiguration extends AbstractConnectionConfiguration {

  static final String CODESCAN_DOMAIN = "codescan.io";

  public static String getSonarCloudUrl() {
    return System.getProperty("sonarlint.internal.sonarcloud.url", "https://app.codescan.io");
  }

  public static boolean isSonarCloudAlias(String serverUrl) {
    return StringUtils.removeEnd(serverUrl, "/").endsWith(CODESCAN_DOMAIN);
  }

  private final String organization;

  private final String serverUrl;

  public SonarCloudConnectionConfiguration(String connectionId, String serverUrl, String organization, boolean disableNotifications) {
    super(connectionId, ConnectionKind.SONARCLOUD, disableNotifications, StringUtils.firstNonBlank(serverUrl, getSonarCloudUrl()));
    this.organization = organization;
    this.serverUrl = StringUtils.firstNonBlank(serverUrl, getSonarCloudUrl());
  }

  public String getOrganization() {
    return organization;
  }

  public String getServerUrl() {
    return serverUrl;
  }

  @Override
  public EndpointParams getEndpointParams() {
    return new EndpointParams(getUrl(), true, organization);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    var that = (SonarCloudConnectionConfiguration) o;
    return Objects.equals(organization, that.organization);
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), organization);
  }
}
