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
import java.net.URISyntaxException;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;

public class SonarCloudActiveEnvironment {

  public static final URI PRODUCTION_URI = URI.create("https://sonarcloud.io");

  public static SonarCloudActiveEnvironment prod() {
    return new SonarCloudActiveEnvironment(PRODUCTION_URI, URI.create("wss://events-api.sonarcloud.io/"));
  }

  // CodeScan: the cloud address used when the IDE passes no cloud environment at start-up
  public static final String CODESCAN_URL_PROPERTY = "sonarlint.internal.sonarcloud.url";
  public static final URI CODESCAN_DEFAULT_URI = URI.create("https://app.codescan.io");

  private static final SonarLintLogger LOG = SonarLintLogger.get();

  /**
   * CodeScan: https://app.codescan.io, or the address in the system property sonarlint.internal.sonarcloud.url.
   * CodeScan has no WebSocket service, so there is no WebSocket address and the WebSocket stays off.
   */
  public static SonarCloudActiveEnvironment codeScanDefault() {
    var fromProperty = System.getProperty(CODESCAN_URL_PROPERTY);
    if (fromProperty == null || fromProperty.isBlank()) {
      return new SonarCloudActiveEnvironment(CODESCAN_DEFAULT_URI, null);
    }
    var uri = parseAbsoluteUri(fromProperty.trim());
    if (uri == null) {
      LOG.warn("Ignoring system property '{}': not an absolute address. Using {}", CODESCAN_URL_PROPERTY, CODESCAN_DEFAULT_URI);
      return new SonarCloudActiveEnvironment(CODESCAN_DEFAULT_URI, null);
    }
    return new SonarCloudActiveEnvironment(uri, null);
  }

  @CheckForNull
  private static URI parseAbsoluteUri(String url) {
    try {
      var uri = new URI(url);
      return uri.isAbsolute() ? uri : null;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private final URI uri;
  private final URI webSocketsEndpointUri;

  public SonarCloudActiveEnvironment(URI uri, @Nullable URI webSocketsEndpointUri) {
    this.uri = uri;
    this.webSocketsEndpointUri = webSocketsEndpointUri;
  }

  public URI getUri() {
    return uri;
  }

  @CheckForNull
  public URI getWebSocketsEndpointUri() {
    return webSocketsEndpointUri;
  }
}
