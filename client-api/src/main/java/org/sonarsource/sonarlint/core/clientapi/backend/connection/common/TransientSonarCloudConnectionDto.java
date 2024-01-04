/*
 * SonarLint Core - Client API
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
package org.sonarsource.sonarlint.core.clientapi.backend.connection.common;

import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.sonarsource.sonarlint.core.clientapi.common.TokenDto;
import org.sonarsource.sonarlint.core.clientapi.common.UsernamePasswordDto;

public class TransientSonarCloudConnectionDto {

  private final String organization;

  private final String serverUrl;

  private final Either<TokenDto, UsernamePasswordDto> credentials;

  public TransientSonarCloudConnectionDto(String serverUrl, String organization, Either<TokenDto, UsernamePasswordDto> credentials) {
    this.organization = organization;
    this.credentials = credentials;
    this.serverUrl = serverUrl;
  }

  public String getOrganization() {
    return organization;
  }

  public String getServerUrl() {
    return serverUrl;
  }

  public Either<TokenDto, UsernamePasswordDto> getCredentials() {
    return credentials;
  }
}
