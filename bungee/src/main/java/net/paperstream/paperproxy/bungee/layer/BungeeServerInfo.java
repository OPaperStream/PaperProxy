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
import com.velocitypowered.api.proxy.server.ServerInfo;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/**
 * A Bungee {@link net.md_5.bungee.api.config.ServerInfo}. It always refers to a server by name;
 * the Velocity server behind it is looked up on every call, so registrations made later (or by
 * Velocity plugins) are seen immediately.
 */
final class BungeeServerInfo implements net.md_5.bungee.api.config.ServerInfo {

  private final BungeeLayer layer;
  private final String name;
  private final InetSocketAddress address;
  private final String motd;
  private final boolean restricted;

  BungeeServerInfo(final BungeeLayer layer, final String name, final InetSocketAddress address,
                   final String motd, final boolean restricted) {
    this.layer = layer;
    this.name = name;
    this.address = address;
    this.motd = motd;
    this.restricted = restricted;
  }

  /**
   * Returns the Velocity server with this name, if it is registered.
   *
   * @return the server
   */
  Optional<RegisteredServer> registered() {
    return layer.velocity().getServer(name);
  }

  /**
   * Returns the Velocity server, registering it first when a plugin created this info itself.
   *
   * @return the server
   */
  RegisteredServer registeredOrCreate() {
    return registered().orElseGet(() -> layer.velocity().registerServer(velocityInfo()));
  }

  ServerInfo velocityInfo() {
    return new ServerInfo(name, address);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public InetSocketAddress getAddress() {
    return address;
  }

  @Override
  public SocketAddress getSocketAddress() {
    return address;
  }

  @Override
  public Collection<ProxiedPlayer> getPlayers() {
    return registered()
        .map(server -> server.getPlayersConnected().stream()
            .map(layer::player)
            .map(ProxiedPlayer.class::cast)
            .toList())
        .orElse(List.of());
  }

  @Override
  public String getMotd() {
    return motd;
  }

  @Override
  public boolean isRestricted() {
    return restricted;
  }

  @Override
  public String getPermission() {
    return "bungeecord.server." + name;
  }

  @Override
  public boolean canAccess(final CommandSender sender) {
    return !restricted || sender.hasPermission(getPermission());
  }

  @Override
  public void sendData(final String channel, final byte[] data) {
    sendData(channel, data, true);
  }

  @Override
  public boolean sendData(final String channel, final byte[] data, final boolean queue) {
    return registered()
        .map(server -> server.sendPluginMessage(Channels.identifier(channel), data))
        .orElse(false);
  }

  @Override
  public void ping(final Callback<ServerPing> callback) {
    final RegisteredServer server = registered()
        .orElseGet(() -> layer.velocity().createRawRegisteredServer(velocityInfo()));
    server.ping().whenComplete((ping, error) -> {
      if (error != null) {
        callback.done(null, error);
      } else {
        callback.done(Pings.toBungee(ping), null);
      }
    });
  }

  @Override
  public boolean equals(final Object o) {
    return o instanceof BungeeServerInfo other && other.name.equals(name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name);
  }

  @Override
  public String toString() {
    return "ServerInfo(name=" + name + ", address=" + address + ")";
  }
}
