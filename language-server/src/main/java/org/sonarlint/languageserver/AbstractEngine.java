/*
 * SonarLint Language Server
 * Copyright (C) 2009-2018 SonarSource SA
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
package org.sonarlint.languageserver;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nullable;

import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;
import org.sonarsource.sonarlint.core.client.api.common.LogOutput;
import org.sonarsource.sonarlint.core.client.api.common.ProgressMonitor;
import org.sonarsource.sonarlint.core.client.api.common.RuleDetails;
import org.sonarsource.sonarlint.core.client.api.common.analysis.AnalysisResults;
import org.sonarsource.sonarlint.core.client.api.common.analysis.ClientInputFile;
import org.sonarsource.sonarlint.core.client.api.common.analysis.IssueListener;
import org.sonarsource.sonarlint.core.client.api.standalone.StandaloneAnalysisConfiguration;

public abstract class AbstractEngine {
  protected final LanguageClient client;
  protected final LogOutput logOutput;
  public abstract void stop();
  public abstract RuleDetails getRuleDetails(String ruleKey);
  public abstract AnalysisResults analyze(Path baseDir, Iterable<ClientInputFile> inputFiles,
      Map<String, String> extraProperties, 
      IssueListener issueListener, 
      LogOutput logOutput, ProgressMonitor monitor);
  
  protected AbstractEngine(LanguageClient client, LogOutput logOutput) {
    this.logOutput = logOutput;
    this.client = client;
  }

  protected void warn(String message) {
    client.logMessage(new MessageParams(MessageType.Warning, message));
  }
  protected void debug(String message) {
    client.logMessage(new MessageParams(MessageType.Log, message));
  }
}
