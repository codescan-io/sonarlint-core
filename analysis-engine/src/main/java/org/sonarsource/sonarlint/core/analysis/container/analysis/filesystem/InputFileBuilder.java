/*
 * SonarLint Core - Analysis Engine
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
package org.sonarsource.sonarlint.core.analysis.container.analysis.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.List;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputFile.Type;
import org.sonarsource.sonarlint.core.analysis.api.ClientInputFile;
import org.sonarsource.sonarlint.core.analysis.container.analysis.issue.ignore.scanner.IssueExclusionsLoader;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;

public class InputFileBuilder {
  private static final SonarLintLogger LOG = SonarLintLogger.get();
  private final LanguageDetection langDetection;
  private final FileMetadata fileMetadata;
  private final IssueExclusionsLoader exclusionsScanner;

  public InputFileBuilder(LanguageDetection langDetection, FileMetadata fileMetadata, IssueExclusionsLoader exclusionsScanner) {
    this.langDetection = langDetection;
    this.fileMetadata = fileMetadata;
    this.exclusionsScanner = exclusionsScanner;
  }

  SonarLintInputFile create(ClientInputFile inputFile) {
    var defaultInputFile = new SonarLintInputFile(inputFile, f -> {
      LOG.debug("Initializing metadata of file {}", f.uri());
      var charset = f.charset();
      InputStream stream;
      try {
        stream = f.inputStream();
      } catch (IOException e) {
        throw new IllegalStateException("Failed to open a stream on file: " + f.uri(), e);
      }
      return fileMetadata.readMetadata(stream, charset != null ? charset : Charset.defaultCharset(), f.uri(), exclusionsScanner.createCharHandlerFor(f));
    }, buildDependencyInputFiles(inputFile));
    defaultInputFile.setType(inputFile.isTest() ? Type.TEST : Type.MAIN);
    var fileLanguage = inputFile.language();
    if (fileLanguage != null) {
      LOG.debug("Language of file \"{}\" is set to \"{}\"", inputFile.uri(), fileLanguage);
      defaultInputFile.setLanguage(fileLanguage);
    } else {
      defaultInputFile.setLanguage(langDetection.language(defaultInputFile));
    }

    return defaultInputFile;
  }

  private List<InputFile> buildDependencyInputFiles(ClientInputFile inputFile) {
    List<ClientInputFile> dependencyFiles = inputFile.getReferenceFiles();
    if (dependencyFiles == null) return null;

    List<InputFile> result = new LinkedList<>();
    for (var dependencyFile : dependencyFiles) {
      result.add(toSonarLintInputFile(dependencyFile, inputFile));
    }
    return result;
  }

  private SonarLintInputFile toSonarLintInputFile(ClientInputFile dependencyFile, ClientInputFile parentFile) {
    return new SonarLintInputFile(dependencyFile, f -> {
      LOG.debug("Initializing metadata of dependency file {} for {}", f.uri(), parentFile.uri());
      try {
        var charset = f.charset();
        return fileMetadata.readMetadata(f.inputStream(), charset != null ? charset : Charset.defaultCharset(),
                  f.uri(), exclusionsScanner.createCharHandlerFor(f));
      } catch (IOException e) {
        throw new IllegalStateException("Failed to open stream for dependency file: " + f.uri(), e);
      }
    });
  }

}
