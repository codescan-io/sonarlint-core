/*
 * SonarLint Core - Commons
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
package org.sonarsource.sonarlint.core.commons.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SonarLanguageTests {

  @Test
  void apex_is_served_by_the_codescan_analyzer() {
    var apex = SonarLanguage.forKey("sf");

    assertThat(apex).isPresent();
    assertThat(apex.get().name()).isEqualTo("APEX");
    assertThat(apex.get().getPluginKey()).isEqualTo("codescan");
    assertThat(apex.get().getDefaultFileSuffixes()).containsExactly(".cls", ".trigger");
    assertThat(apex.get().getFileSuffixesPropKey()).isEqualTo("sf.apex.suffixes");
  }

  @Test
  void visualforce_and_lightning_are_served_by_the_codescan_analyzer() {
    var vf = SonarLanguage.forKey("vf");

    assertThat(vf).isPresent();
    assertThat(vf.get().name()).isEqualTo("VF");
    assertThat(vf.get().getPluginKey()).isEqualTo("codescan");
    assertThat(vf.get().getDefaultFileSuffixes()).containsExactly(".page", ".component", ".app", ".evt", ".cmp", ".intf");
    assertThat(vf.get().getFileSuffixesPropKey()).isEqualTo("sf.vf.suffixes");
  }

  @Test
  void salesforce_metadata_is_served_by_the_codescan_analyzer() {
    var sfMeta = SonarLanguage.forKey("sfmeta");

    assertThat(sfMeta).isPresent();
    assertThat(sfMeta.get().name()).isEqualTo("SF_META");
    assertThat(sfMeta.get().getPluginKey()).isEqualTo("codescan");
    assertThat(sfMeta.get().getDefaultFileSuffixes()).containsExactly(".settings", ".object");
    assertThat(sfMeta.get().getFileSuffixesPropKey()).isEqualTo("sf.sfmeta.suffixes");
  }

  @Test
  void codescan_analyzer_serves_exactly_the_three_salesforce_languages() {
    assertThat(SonarLanguage.getLanguagesByPluginKey("codescan")).extracting(Enum::name).containsExactlyInAnyOrder("APEX", "VF", "SF_META");
    assertThat(SonarLanguage.containsPlugin("sonarapex")).isFalse();
  }

  @Test
  void xml_does_not_claim_plain_xml_files_by_default() {
    // CodeScan (row C5, kept from the 9.1.3 fork): plain .xml files are not sent to the XML analyzer unless a setting says so
    assertThat(SonarLanguage.XML.getDefaultFileSuffixes()).containsExactly(".xsd", ".xsl");
  }

}
