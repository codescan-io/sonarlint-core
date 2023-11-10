/*
 * SonarLint Core - RPC Protocol
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
package org.sonarsource.sonarlint.core.rpc.protocol.client.progress;

import com.google.gson.annotations.JsonAdapter;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.validation.NonNull;
import org.sonarsource.sonarlint.core.rpc.protocol.adapter.EitherProgressNotificationAdapterFactory;

public class ReportProgressParams {
  /**
   * The task ID is a unique identifier, generated by the backend that identifies a long-running task.
   * The same ID needs to be re-used when reporting progress to the client for a given task.
   */
  private final String taskId;

  @JsonAdapter(EitherProgressNotificationAdapterFactory.class)
  private final Either<ProgressUpdateNotification, ProgressEndNotification> notification;

  public ReportProgressParams(@NonNull String taskId, @NonNull ProgressUpdateNotification notification) {
    this(taskId, Either.forLeft(notification));
  }

  public ReportProgressParams(@NonNull String taskId, @NonNull ProgressEndNotification notification) {
    this(taskId, Either.forRight(notification));
  }

  public ReportProgressParams(@NonNull String taskId, @NonNull Either<ProgressUpdateNotification, ProgressEndNotification> notification) {
    this.taskId = taskId;
    this.notification = notification;
  }

  public String getTaskId() {
    return taskId;
  }

  public Either<ProgressUpdateNotification, ProgressEndNotification> getNotification() {
    return notification;
  }
}
