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

import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.LoginPhaseConnection;
import com.velocitypowered.api.proxy.Player;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A connection during handshake, ping and login, or the login data of an online player.
 */
final class BungeePendingConnection implements PendingConnection {

  private final BungeeLayer layer;
  private final InboundConnection connection;
  private @Nullable String name;
  private @Nullable UUID uniqueId;
  private @Nullable Boolean onlineMode;

  BungeePendingConnection(final BungeeLayer layer, final InboundConnection connection,
                          final @Nullable String name, final @Nullable UUID uniqueId) {
    this.layer = layer;
    this.connection = connection;
    this.name = name;
    this.uniqueId = uniqueId;
  }

  /**
   * Returns the online mode a plugin forced through {@link #setOnlineMode}, if any.
   *
   * @return true/false when forced, null otherwise
   */
  @Nullable Boolean forcedOnlineMode() {
    return onlineMode;
  }

  void setName(final String name) {
    this.name = name;
  }

  @Override
  public String getName() {
    if (name == null && connection instanceof Player player) {
      return player.getUsername();
    }
    return name;
  }

  @Override
  public int getVersion() {
    return connection.getProtocolVersion().getProtocol();
  }

  @Override
  public InetSocketAddress getVirtualHost() {
    return connection.getVirtualHost().orElse(null);
  }

  @Override
  public ListenerInfo getListener() {
    return layer.listenerInfo();
  }

  @Override
  @Deprecated
  public String getUUID() {
    final UUID id = getUniqueId();
    return id == null ? null : id.toString().replace("-", "");
  }

  @Override
  public UUID getUniqueId() {
    if (connection instanceof Player player) {
      return player.getUniqueId();
    }
    return uniqueId;
  }

  @Override
  public void setUniqueId(final UUID uuid) {
    if (connection instanceof Player) {
      throw new IllegalStateException("Can only set the UUID during PreLoginEvent");
    }
    this.uniqueId = uuid;
  }

  @Override
  public boolean isOnlineMode() {
    if (connection instanceof Player player) {
      return player.isOnlineMode();
    }
    return onlineMode != null ? onlineMode : layer.velocity().getConfiguration().isOnlineMode();
  }

  @Override
  public void setOnlineMode(final boolean onlineMode) {
    if (connection instanceof Player) {
      throw new IllegalStateException("Can only set online mode during PreLoginEvent");
    }
    this.onlineMode = onlineMode;
  }

  @Override
  public boolean isLegacy() {
    return false;
  }

  @Override
  public boolean isTransferred() {
    return false;
  }

  @Override
  public CompletableFuture<byte[]> retrieveCookie(final String cookie) {
    if (connection instanceof Player player) {
      return layer.cookies().request(player, cookie);
    }
    return CompletableFuture.failedFuture(
        new IllegalStateException("Cookies can only be requested from online players"));
  }

  @Override
  public CompletableFuture<byte[]> sendData(final String channel, final byte[] data) {
    if (!(connection instanceof LoginPhaseConnection login)) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Login plugin messages only work during login"));
    }
    final CompletableFuture<byte[]> future = new CompletableFuture<>();
    login.sendLoginPluginMessage(Channels.identifier(channel), data, future::complete);
    return future;
  }

  @Override
  @Deprecated
  public InetSocketAddress getAddress() {
    return connection.getRemoteAddress();
  }

  @Override
  public SocketAddress getSocketAddress() {
    return connection.getRemoteAddress();
  }

  @Override
  @Deprecated
  public void disconnect(final String reason) {
    disconnect0(Chat.fromLegacy(reason));
  }

  @Override
  public void disconnect(final BaseComponent... reason) {
    disconnect0(Chat.toAdventure(reason));
  }

  @Override
  public void disconnect(final BaseComponent reason) {
    disconnect0(Chat.toAdventure(reason));
  }

  private void disconnect0(final Component reason) {
    if (connection instanceof Player player) {
      player.disconnect(reason);
    } else {
      Unsupported.ignored("PendingConnection.disconnect",
          "use the cancel/reason of the login events instead");
    }
  }

  @Override
  public boolean isConnected() {
    return connection.isActive();
  }

  @Override
  public Unsafe unsafe() {
    return Unsupported.PACKETS;
  }
}
