/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.paperstream.paperproxy.sync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A minimal Redis client speaking RESP2, enough for PaperProxy's network sync: plain commands
 * and a subscription loop. One instance is one connection and is not shared between threads
 * while subscribed.
 */
final class RedisConnection implements AutoCloseable {

  /** A Redis error reply. */
  static final class RedisException extends IOException {

    private static final long serialVersionUID = 1L;

    RedisException(final String message) {
      super(message);
    }
  }

  /** Replies PaperProxy expects are small; bigger ones mean a broken or hostile server. */
  static final int MAX_ELEMENTS = 10_000;
  static final int MAX_BULK_BYTES = 1024 * 1024;
  static final int MAX_LINE_BYTES = 8 * 1024;

  private final Socket socket;
  private final InputStream in;
  private final OutputStream out;

  private RedisConnection(final Socket socket) throws IOException {
    this(socket, socket.getInputStream(), socket.getOutputStream());
  }

  RedisConnection(final Socket socket, final InputStream in, final OutputStream out) {
    this.socket = socket;
    this.in = new BufferedInputStream(in);
    this.out = new BufferedOutputStream(out);
  }

  /**
   * Connects, authenticates and selects the database.
   *
   * @param host the host
   * @param port the port
   * @param username the ACL user (Redis 6+), empty for the default user
   * @param password the password, empty for none
   * @param database the database number
   * @param ssl whether to use TLS, with host name verification
   * @param timeoutMillis connect and read timeout
   * @return the connection
   * @throws IOException if Redis cannot be reached or rejects the login
   */
  static RedisConnection open(final String host, final int port, final String username,
                              final String password, final int database, final boolean ssl,
                              final int timeoutMillis) throws IOException {
    Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(host, port), timeoutMillis);
      socket.setSoTimeout(timeoutMillis);
      socket.setTcpNoDelay(true);
      socket.setKeepAlive(true);
      if (ssl) {
        final SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
            .createSocket(socket, host, port, true);
        final SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        tls.startHandshake();
        socket = tls;
      }
      final RedisConnection connection = new RedisConnection(socket);
      if (!password.isEmpty()) {
        if (username.isEmpty()) {
          connection.call("AUTH", password);
        } else {
          connection.call("AUTH", username, password);
        }
      }
      if (database != 0) {
        connection.call("SELECT", String.valueOf(database));
      }
      return connection;
    } catch (final IOException e) {
      socket.close();
      throw e;
    }
  }

  /**
   * Sends a command and reads its reply.
   *
   * @param args the command and its arguments
   * @return a String, Long, List or null
   * @throws IOException on connection problems or an error reply
   */
  synchronized @Nullable Object call(final String... args) throws IOException {
    write(args);
    return read();
  }

  /**
   * Turns this connection into a subscriber and blocks, handing every message to the handler,
   * until the connection breaks or is closed.
   *
   * @param handler receives channel and message
   * @param channels the channels
   * @throws IOException when the connection ends
   */
  void subscribe(final MessageHandler handler, final String... channels) throws IOException {
    final String[] args = new String[channels.length + 1];
    args[0] = "SUBSCRIBE";
    System.arraycopy(channels, 0, args, 1, channels.length);
    // Subscriptions wait for messages, so no read timeout here.
    socket.setSoTimeout(0);
    write(args);
    while (!socket.isClosed()) {
      final Object reply = read();
      if (reply instanceof List<?> list && list.size() == 3 && "message".equals(list.get(0))) {
        handler.onMessage(String.valueOf(list.get(1)), String.valueOf(list.get(2)));
      }
    }
  }

  /** Receives Pub/Sub messages. */
  @FunctionalInterface
  interface MessageHandler {

    /**
     * Called for every message.
     *
     * @param channel the channel
     * @param message the message
     */
    void onMessage(String channel, String message);
  }

  private void write(final String[] args) throws IOException {
    final StringBuilder header = new StringBuilder().append('*').append(args.length)
        .append("\r\n");
    out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
    for (final String arg : args) {
      final byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
      out.write(('$' + String.valueOf(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
      out.write(bytes);
      out.write('\r');
      out.write('\n');
    }
    out.flush();
  }

  private @Nullable Object read() throws IOException {
    final int type = in.read();
    if (type == -1) {
      throw new EOFException("Redis closed the connection");
    }
    final String line = readLine();
    switch (type) {
      case '+':
        return line;
      case '-':
        throw new RedisException(line);
      case ':':
        return Long.parseLong(line);
      case '$': {
        final int length = Integer.parseInt(line);
        if (length < 0) {
          return null;
        }
        if (length > MAX_BULK_BYTES) {
          throw new IOException("Redis reply too big (" + length + " bytes)");
        }
        final byte[] data = in.readNBytes(length);
        if (data.length != length) {
          throw new EOFException("Redis closed the connection");
        }
        readLine();
        return new String(data, StandardCharsets.UTF_8);
      }
      case '*': {
        final int count = Integer.parseInt(line);
        if (count < 0) {
          return null;
        }
        if (count > MAX_ELEMENTS) {
          throw new IOException("Redis reply has too many elements (" + count + ")");
        }
        final List<Object> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
          items.add(read());
        }
        return items;
      }
      default:
        throw new IOException("Unexpected Redis reply type '" + (char) type + "'");
    }
  }

  private String readLine() throws IOException {
    final StringBuilder line = new StringBuilder();
    while (true) {
      final int c = in.read();
      if (c == -1) {
        throw new EOFException("Redis closed the connection");
      }
      if (c == '\r') {
        if (in.read() != '\n') {
          throw new IOException("Malformed Redis reply");
        }
        return line.toString();
      }
      if (line.length() >= MAX_LINE_BYTES) {
        throw new IOException("Redis reply line too long");
      }
      line.append((char) c);
    }
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
