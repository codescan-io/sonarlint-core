/*
 * SonarLint Core - Server API
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
package org.sonarsource.sonarlint.core.serverapi.util;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import org.sonarsource.sonarlint.core.serverapi.proto.sonarqube.ws.Common.TextRange;

public class ServerApiUtils {

  public static final String DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ";

  private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern(DATETIME_FORMAT);

  public static String extractCodeSnippet(@Nullable String sourceCode, TextRange textRange) {
    if (sourceCode == null || sourceCode.isEmpty()) {
      return "";
    }
    return extractCodeSnippet(sourceCode.split("\\r?\\n"), textRange);
  }

  private static String extractCodeSnippet(String[] sourceCodeLines, TextRange textRange) {
    // Validate textRange parameters
    if (textRange.getStartLine() <= 0 || textRange.getEndLine() <= 0) {
      return "";
    }
    if (textRange.getStartLine() > textRange.getEndLine()) {
      return "";
    }
    if (textRange.getStartLine() > sourceCodeLines.length || textRange.getEndLine() > sourceCodeLines.length) {
      return "";
    }
    
    if (textRange.getStartLine() == textRange.getEndLine()) {
      var lineIndex = textRange.getStartLine() - 1;
      var fullline = sourceCodeLines[lineIndex];
      
      // Validate offsets within the line
      if (textRange.getStartOffset() < 0 || textRange.getEndOffset() < 0) {
        return "";
      }
      if (textRange.getStartOffset() > textRange.getEndOffset()) {
        return "";
      }
      if (textRange.getStartOffset() > fullline.length() || textRange.getEndOffset() > fullline.length()) {
        return "";
      }
      
      return fullline.substring(textRange.getStartOffset(), textRange.getEndOffset());
    } else {
      var linesOfTextRange = Arrays.copyOfRange(sourceCodeLines, textRange.getStartLine() - 1, textRange.getEndLine());
      
      // Validate start offset for first line
      if (textRange.getStartOffset() < 0 || textRange.getStartOffset() > linesOfTextRange[0].length()) {
        return "";
      }
      
      // Validate end offset for last line
      if (textRange.getEndOffset() < 0 || textRange.getEndOffset() > linesOfTextRange[linesOfTextRange.length - 1].length()) {
        return "";
      }
      
      linesOfTextRange[0] = linesOfTextRange[0].substring(textRange.getStartOffset());
      linesOfTextRange[linesOfTextRange.length - 1] = linesOfTextRange[linesOfTextRange.length - 1].substring(0, textRange.getEndOffset());
      return String.join("\n", linesOfTextRange);
    }
  }

  public static boolean isBlank(@Nullable Collection<?> collection) {
    return collection == null || collection.isEmpty();
  }

  public static boolean isBlank(@Nullable String s) {
    return s == null || s.isEmpty();
  }

  public static boolean areBlank(List<?>... lists) {
    return Arrays.stream(lists).allMatch(ServerApiUtils::isBlank);
  }

  public static OffsetDateTime parseOffsetDateTime(String s) {
    try {
      return OffsetDateTime.parse(s, DATETIME_FORMATTER);
    } catch (DateTimeParseException e) {
      throw new IllegalStateException("The date '" + s + "' does not respect format '" + DATETIME_FORMAT + "'", e);
    }
  }

  private ServerApiUtils() {
    // utility class
  }

}
