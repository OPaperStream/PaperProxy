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

package net.paperstream.paperproxy.network;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.query.ProxyQueryEvent;
import com.velocitypowered.api.proxy.server.QueryResponse;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Answers proxy queries with the map, version and plugin list of one backend server, so query
 * tools show the real game server instead of the proxy. Player counts stay the proxy's own.
 */
public final class QueryPassthrough {

  private static final long MAX_AGE_MILLIS = 10_000;
  private static final int TIMEOUT_MILLIS = 1_000;

  private final VelocityServer server;
  private final AtomicBoolean refreshing = new AtomicBoolean();
  private volatile @Nullable Map<String, String> cached;
  private volatile String cachedFor = "";
  private volatile long cachedAt;

  /**
   * Creates the listener.
   *
   * @param server the proxy
   */
  public QueryPassthrough(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Replaces the proxy's details with the backend's.
   *
   * @param event the event
   */
  @Subscribe
  public void onQuery(final ProxyQueryEvent event) {
    final String name = server.getPaperProxyConfig().values().queryServer();
    if (name.isEmpty()) {
      return;
    }
    final Optional<RegisteredServer> target = server.getServer(name);
    if (target.isEmpty()) {
      return;
    }
    Map<String, String> data = cached;
    final boolean stale = data == null || !cachedFor.equals(name)
        || System.currentTimeMillis() - cachedAt > MAX_AGE_MILLIS;
    if (stale && refreshing.compareAndSet(false, true)) {
      try {
        final InetSocketAddress game = target.get().getServerInfo().getAddress();
        final int port = server.getPaperProxyConfig().values().queryPort();
        data = query(new InetSocketAddress(game.getHostString(),
            port == 0 ? game.getPort() : port));
        cached = data;
        cachedFor = name;
        cachedAt = System.currentTimeMillis();
      } catch (final IOException e) {
        // Backend query is off or the server is down: keep the proxy's own answer.
        cached = null;
        data = null;
      } finally {
        refreshing.set(false);
      }
    }
    if (data == null || !cachedFor.equals(name)) {
      return;
    }
    event.setResponse(apply(event.getResponse(), data));
  }

  static QueryResponse apply(final QueryResponse response, final Map<String, String> data) {
    final QueryResponse.Builder builder = response.toBuilder();
    if (data.containsKey("map")) {
      builder.map(data.get("map"));
    }
    if (data.containsKey("version")) {
      builder.gameVersion(data.get("version"));
    }
    final String plugins = data.get("plugins");
    if (plugins != null && !plugins.isEmpty()) {
      final int colon = plugins.indexOf(": ");
      builder.proxyVersion(colon < 0 ? plugins : plugins.substring(0, colon));
      builder.clearPlugins();
      if (colon >= 0) {
        for (final String plugin : plugins.substring(colon + 2).split("; ")) {
          if (plugin.isBlank()) {
            continue;
          }
          final int space = plugin.lastIndexOf(' ');
          builder.plugins(space < 0
              ? QueryResponse.PluginInformation.of(plugin, null)
              : QueryResponse.PluginInformation.of(plugin.substring(0, space),
                  plugin.substring(space + 1)));
        }
      }
    }
    return builder.build();
  }

  /**
   * Runs a full GS4 query against a server.
   *
   * @param address the query address
   * @return the key value section of the answer
   * @throws IOException if the server does not answer
   */
  static Map<String, String> query(final InetSocketAddress address) throws IOException {
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(TIMEOUT_MILLIS);
      socket.connect(address);
      final int session = ThreadLocalRandom.current().nextInt() & 0x0F0F0F0F;

      final byte[] handshake = ByteBuffer.allocate(7).put((byte) 0xFE).put((byte) 0xFD)
          .put((byte) 9).putInt(session).array();
      final byte[] reply = exchange(socket, handshake);
      final int token = Integer.parseInt(readString(reply, 5).trim());

      final byte[] stat = ByteBuffer.allocate(15).put((byte) 0xFE).put((byte) 0xFD)
          .put((byte) 0).putInt(session).putInt(token).putInt(0).array();
      return parseFull(exchange(socket, stat));
    } catch (final NumberFormatException e) {
      throw new IOException("bad challenge token", e);
    }
  }

  static Map<String, String> parseFull(final byte[] data) throws IOException {
    // 1 type byte, 4 session bytes, 11 padding bytes, then key\0value\0 ... \0
    int pos = 16;
    final Map<String, String> out = new HashMap<>();
    while (pos < data.length && data[pos] != 0) {
      final String key = readString(data, pos);
      pos += key.getBytes(StandardCharsets.ISO_8859_1).length + 1;
      final String value = readString(data, pos);
      pos += value.getBytes(StandardCharsets.ISO_8859_1).length + 1;
      out.put(key, value);
    }
    if (out.isEmpty()) {
      throw new IOException("empty query answer");
    }
    return out;
  }

  private static byte[] exchange(final DatagramSocket socket, final byte[] request)
      throws IOException {
    socket.send(new DatagramPacket(request, request.length));
    final byte[] buffer = new byte[8192];
    final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    socket.receive(packet);
    final byte[] out = new byte[packet.getLength()];
    System.arraycopy(buffer, 0, out, 0, out.length);
    return out;
  }

  private static String readString(final byte[] data, final int start) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = start; i < data.length && data[i] != 0; i++) {
      out.write(data[i]);
    }
    return out.toString(StandardCharsets.ISO_8859_1);
  }
}
