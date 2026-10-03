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

import com.velocitypowered.api.proxy.ServerConnection;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Server;

/**
 * A player's connection to a backend server.
 */
final class BungeeServer implements Server {

  private final BungeeLayer layer;
  private final ServerConnection connection;

  BungeeServer(final BungeeLayer layer, final ServerConnection connection) {
    this.layer = layer;
    this.connection = connection;
  }

  ServerConnection connection() {
    return connection;
  }

  @Override
  public ServerInfo getInfo() {
    return layer.serverInfo(connection.getServerInfo().getName());
  }

  @Override
  public void sendData(final String channel, final byte[] data) {
    connection.sendPluginMessage(Channels.identifier(channel), data);
  }

  @Override
  @Deprecated
  public InetSocketAddress getAddress() {
    return connection.getServerInfo().getAddress();
  }

  @Override
  public SocketAddress getSocketAddress() {
    return connection.getServerInfo().getAddress();
  }

  @Override
  @Deprecated
  public void disconnect(final String reason) {
    disconnect(Chat.toBungee(Chat.fromLegacy(reason)));
  }

  @Override
  public void disconnect(final BaseComponent... reason) {
    Unsupported.ignored("Server.disconnect", "closing only the backend connection");
  }

  @Override
  public void disconnect(final BaseComponent reason) {
    disconnect(new BaseComponent[] {reason});
  }

  @Override
  public boolean isConnected() {
    return connection.getPlayer().isActive()
        && connection.getPlayer().getCurrentServer().map(connection::equals).orElse(false);
  }

  @Override
  public Unsafe unsafe() {
    return Unsupported.PACKETS;
  }
}
