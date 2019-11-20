/*
 * SonarLint Language Server
 * Copyright (C) 2009-2019 SonarSource SA
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ChromeNativeMessaging {
  
  public static class Out extends OutputStream {
    private final StringBuilder header = new StringBuilder();
    private int contentRemaining = -1;
    private final OutputStream out;

    public Out() {
      this.out = System.out;
      //redirect stdout to stderr
      System.setOut(System.err);
    }
    public Out(OutputStream out) {
      this.out = out;
    }
    
    private int isHeaderEnd() throws IOException {
      if ( header.length() < 5 )
        return -1;
      
      int from = header.length()-4;
      int to = header.length();
      char c;
      for ( int i=from;i<to;i++) {
        c = header.charAt(i);
        if ( c != '\r' && c != '\n')
          return -1;
      }
      
//      System.err.println("Headers: " + header);
      for ( String line : header.toString().split("\r\n") ) {
        if ( line.startsWith("Content-Length: ") ) {
          int contentLength = Integer.parseInt(line.substring("Content-Length: ".length()));
          byte[] byte4 = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(contentLength).array();
//          System.err.println("write=" + byte4[0]);
//          System.err.println("write=" + byte4[1]);
//          System.err.println("write=" + byte4[2]);
//          System.err.println("write=" + byte4[3]);
//          System.err.println("write contentLength=" + contentLength);
          out.write(byte4);
          header.setLength(0);
          return contentLength;
        }
      }

      header.setLength(0);
      return 0;
    }

    public void write(ByteBuffer r, ByteBuffer w) throws IOException {
      while ( r.remaining() > 0 ) {
        if ( contentRemaining <= 0 ) {
          header.append((char)r.get());
          contentRemaining = isHeaderEnd();
        }else {
          byte next = r.get();
//          System.err.println("w: " + (char)next + " remaining: " + contentRemaining);
          w.put(next);
          contentRemaining--;
        }
      }
    }

    @Override
    public void write(int b) throws IOException {
      ByteBuffer r = ByteBuffer.wrap(new byte[] {(byte)b});
      ByteBuffer w = ByteBuffer.allocate(1);
      write(r, w);
      int l = w.position();
      if ( l > 0 ) {
        w.rewind();
        out.write(w.get());
      }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      ByteBuffer r = ByteBuffer.wrap(b, off, len);
      ByteBuffer w = ByteBuffer.allocate(len);
      write(r, w);
      int l = w.position();
      if ( l > 0 ) {
        w.rewind();
        out.write(w.array(), off, l);
      }
    }
  }
  
  public static class In extends InputStream{
    private final InputStream in;
    private ByteBuffer header = null;
    private int contentRemaining = -1;

    public In() {
      this.in = System.in;
    }
    public In(InputStream out) {
      this.in = out;
    }
    
    public void read(ByteBuffer ret) throws IOException {
      while ( ret.remaining() > 0 ) {
        if ( header != null ) {
          int next = header.get();
          if ( header.remaining() == 0 )
            header = null;
          ret.put((byte)next);
        }else if ( contentRemaining <= 0 ){
          ByteBuffer len = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
          int read = in.read(len.array());
          if ( read != 4 )
            break;
          contentRemaining = len.getInt();
          
          len.rewind();
//          System.err.println("read=" + len.get());
//          System.err.println("read=" + len.get());
//          System.err.println("read=" + len.get());
//          System.err.println("read=" + len.get());
  
          len.rewind();
          header = ByteBuffer.wrap(("Content-Length: " + contentRemaining + "\r\n\r\n").getBytes());
//          System.err.println("read=" + new String(header.array()));
          ret.put(header.get());
        }else {
          contentRemaining--;
          int next = in.read();
//          System.err.println("r: " + (char)next + " remaining: " + contentRemaining);
          ret.put((byte)next);
        }
      }
    }

    @Override
    public int read() throws IOException {
      ByteBuffer r = ByteBuffer.allocate(1);
      read(r);
      r.rewind();
      return r.get();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      ByteBuffer r = ByteBuffer.wrap(b, off, len);
      read(r);
      if ( r.position() == 0 )
        return -1;
      return r.position();
    }
    
  }
}
