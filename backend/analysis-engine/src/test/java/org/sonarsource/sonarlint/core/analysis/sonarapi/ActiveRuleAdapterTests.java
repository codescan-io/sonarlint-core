/*
 * SonarLint Core - Analysis Engine
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
package org.sonarsource.sonarlint.core.analysis.sonarapi;

import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.analysis.api.ActiveRule;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveRuleAdapterTests {

  @Test
  void internal_key_is_the_one_given_by_the_analyzer_rule_definition() {
    // CodeScan analyzers look their rules up by internal key
    var activeRule = new ActiveRule("sf:SomeRule", "sf");
    activeRule.setInternalKey("InternalKeyFromAnalyzer");

    assertThat(new ActiveRuleAdapter(activeRule).internalKey()).isEqualTo("InternalKeyFromAnalyzer");
  }

  @Test
  void internal_key_falls_back_to_the_rule_part_of_the_key_when_the_analyzer_gives_none() {
    var activeRule = new ActiveRule("cpp:S123", "cpp");

    assertThat(new ActiveRuleAdapter(activeRule).internalKey()).isEqualTo("S123");
  }

}
