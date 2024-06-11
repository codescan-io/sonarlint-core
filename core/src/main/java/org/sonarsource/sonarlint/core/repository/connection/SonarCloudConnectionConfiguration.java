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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.sonarsource.sonarlint.core.commons.ConnectionKind;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;
import org.sonarsource.sonarlint.core.serverapi.EndpointParams;

public class SonarCloudConnectionConfiguration extends AbstractConnectionConfiguration {

  static final String[] CODESCAN_DOMAINS = new String[]{"codescan.io", "autorabit.com"};
  static final String CODESCAN_HEALTH_ENDPOINT = "/_codescan/actuator/health";
  private static final String CODESCAN_HEALTH_JSON_RESPONSE = "{\"status\":\"UP\"}";
  private static final String CODESCAN_HEALTH_JSON_RESPONSE_DOWN = "{\"status\":\"DOWN\"}";
  private static final SonarLintLogger LOG = SonarLintLogger.get();

  public static String getSonarCloudUrl() {
    return System.getProperty("sonarlint.internal.sonarcloud.url", "https://app.codescan.io");
  }

  public static boolean isCodeScanCloudAlias(String url) {
    url = removeTrailingSlashesFromUrl(url);
    if (StringUtils.containsAny(url, CODESCAN_DOMAINS)) {
      return true;
    }
    HttpClient httpClient = HttpClient.newHttpClient();
    HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(url + CODESCAN_HEALTH_ENDPOINT))
            .build();
    try {
      HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      int statusCode = httpResponse.statusCode();
      if (statusCode == 200
              && (CODESCAN_HEALTH_JSON_RESPONSE.equals(httpResponse.body())
                || CODESCAN_HEALTH_JSON_RESPONSE_DOWN.equals(httpResponse.body()))) {
        return true;
      } else {
        LOG.info("isCodeScanCloudAlias health check request for host {} failed with status code: {}.", url,
                statusCode);
        return false;
      }
    } catch (Exception e) {
      LOG.error("isCodeScanCloudAlias health check request for host {} gave an exception", url, e);

      return false;
    }
  }

  private static String removeTrailingSlashesFromUrl(String url) {
    String cleanedUrl = url.trim();
    while (cleanedUrl.endsWith("/")) {
      cleanedUrl = cleanedUrl.substring(0, cleanedUrl.length() - 1);
    }
    return cleanedUrl;
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
