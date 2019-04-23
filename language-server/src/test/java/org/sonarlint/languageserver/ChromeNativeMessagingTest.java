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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

public class ChromeNativeMessagingTest {

  @Test
  public void testOut() throws Exception {
    ByteArrayOutputStream res = new ByteArrayOutputStream();
    try(ChromeNativeMessaging.Out out = new ChromeNativeMessaging.Out(res)){
      StringBuilder outData = new StringBuilder();
      outData.append("Content-Length: 10\r\n\r\n");
      outData.append("0123456789");
      out.write(outData.toString().getBytes());
      assertThat(res.toByteArray().length).isEqualTo(14);
      assertThat(res.toByteArray()[4]).isEqualTo((byte)'0');
      assertThat(res.toByteArray()[13]).isEqualTo((byte)'9');

      //try continuing
      outData = new StringBuilder();
      outData.append("Content-Length: 5\r\n\r\n");
      outData.append("01234");
      out.write(outData.toString().getBytes());
      assertThat(res.toByteArray().length).isEqualTo(23);
      assertThat(res.toByteArray()[18]).isEqualTo((byte)'0');
      assertThat(res.toByteArray()[22]).isEqualTo((byte)'4');
    }
  }

  @Test
  public void testIn() throws Exception {
    ByteArrayOutputStream inData = new ByteArrayOutputStream();
    inData.write(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(10).array());
    inData.write("0123456789".getBytes());
    inData.write(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(5).array());
    inData.write("01234".getBytes());
    
    ByteArrayOutputStream res = new ByteArrayOutputStream();
    try(ChromeNativeMessaging.In in = new ChromeNativeMessaging.In(new ByteArrayInputStream(inData.toString().getBytes()))){
      IOUtils.copy(in, res);
      assertThat(new String(res.toByteArray())).isEqualTo("Content-Length: 10\r\n\r\n0123456789Content-Length: 5\r\n\r\n01234");
    }
  }
}

