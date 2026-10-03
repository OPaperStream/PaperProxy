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

package net.paperstream.paperproxy.bungee.layer;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.net.InetSocketAddress;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.md_5.bungee.api.config.ServerInfo;

/**
 * Live view of the proxy's servers as Bungee's {@code getServers()} map. Like on BungeeCord,
 * plugins may add or remove servers through it; that registers them with the proxy.
 */
final class ServerMap extends AbstractMap<String, ServerInfo> {

  private final BungeeLayer layer;

  ServerMap(final BungeeLayer layer) {
    this.layer = layer;
  }

  @Override
  public ServerInfo get(final Object key) {
    return key instanceof String name && layer.velocity().getServer(name).isPresent()
        ? layer.serverInfo(name) : null;
  }

  @Override
  public boolean containsKey(final Object key) {
    return key instanceof String name && layer.velocity().getServer(name).isPresent();
  }

  @Override
  public ServerInfo put(final String key, final ServerInfo value) {
    final ServerInfo previous = remove(key);
    final InetSocketAddress address = value.getAddress();
    layer.velocity().registerServer(new com.velocitypowered.api.proxy.server.ServerInfo(key,
        address));
    layer.forgetServerInfo(key);
    return previous;
  }

  @Override
  public ServerInfo remove(final Object key) {
    if (!(key instanceof String name)) {
      return null;
    }
    final ServerInfo previous = get(name);
    layer.velocity().getServer(name).ifPresent(server ->
        layer.velocity().unregisterServer(server.getServerInfo()));
    layer.forgetServerInfo(name);
    return previous;
  }

  @Override
  public Set<Entry<String, ServerInfo>> entrySet() {
    return new AbstractSet<>() {
      @Override
      public Iterator<Entry<String, ServerInfo>> iterator() {
        final List<Entry<String, ServerInfo>> entries = layer.velocity().getAllServers().stream()
            .map(RegisteredServer::getServerInfo)
            .map(info -> Map.<String, ServerInfo>entry(info.getName(),
                layer.serverInfo(info.getName())))
            .toList();
        final Iterator<Entry<String, ServerInfo>> delegate = entries.iterator();
        return new Iterator<>() {
          private Entry<String, ServerInfo> last;

          @Override
          public boolean hasNext() {
            return delegate.hasNext();
          }

          @Override
          public Entry<String, ServerInfo> next() {
            last = delegate.next();
            return last;
          }

          @Override
          public void remove() {
            ServerMap.this.remove(last.getKey());
          }
        };
      }

      @Override
      public int size() {
        return layer.velocity().getAllServers().size();
      }
    };
  }
}
