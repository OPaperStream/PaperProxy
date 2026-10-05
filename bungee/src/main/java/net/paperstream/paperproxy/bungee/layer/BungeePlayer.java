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

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.PlayerSettings;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.ModInfo;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ServerConnectRequest;
import net.md_5.bungee.api.ServerLink;
import net.md_5.bungee.api.SkinConfiguration;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.dialog.Dialog;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.score.Scoreboard;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A Velocity player seen through the BungeeCord API.
 */
final class BungeePlayer extends AbstractSender implements ProxiedPlayer {

  private final Player player;
  private final BungeePendingConnection pending;
  private final Scoreboard scoreboard = new Scoreboard();
  private volatile String displayName;
  private volatile @Nullable ServerInfo reconnectServer;
  private volatile ServerConnectEvent.Reason nextConnectReason = ServerConnectEvent.Reason.PLUGIN;

  BungeePlayer(final BungeeLayer layer, final Player player) {
    super(layer);
    this.player = player;
    this.pending = new BungeePendingConnection(layer, player, player.getUsername(),
        player.getUniqueId());
    this.displayName = player.getUsername();
  }

  Player velocity() {
    return player;
  }

  @Override
  CommandSource source() {
    return player;
  }

  /**
   * Returns and resets the reason of the connection a plugin started last.
   *
   * @return the reason for the next ServerConnectEvent
   */
  ServerConnectEvent.Reason takeConnectReason() {
    final ServerConnectEvent.Reason reason = nextConnectReason;
    nextConnectReason = ServerConnectEvent.Reason.PLUGIN;
    return reason;
  }

  @Override
  public String getName() {
    return player.getUsername();
  }

  @Override
  public String getDisplayName() {
    return displayName;
  }

  @Override
  public void setDisplayName(final String name) {
    this.displayName = name == null ? player.getUsername() : name;
  }

  @Override
  public void sendMessage(final ChatMessageType position, final BaseComponent... message) {
    if (position == ChatMessageType.ACTION_BAR) {
      player.sendActionBar(Chat.toAdventure(message));
    } else {
      player.sendMessage(Chat.toAdventure(message));
    }
  }

  @Override
  public void sendMessage(final ChatMessageType position, final BaseComponent message) {
    sendMessage(position, new BaseComponent[] {message});
  }

  @Override
  public void sendMessage(final UUID sender, final BaseComponent... message) {
    player.sendMessage(Chat.toAdventure(message));
  }

  @Override
  public void sendMessage(final UUID sender, final BaseComponent message) {
    player.sendMessage(Chat.toAdventure(message));
  }

  @Override
  public void connect(final ServerInfo target) {
    connect(target, null, ServerConnectEvent.Reason.PLUGIN);
  }

  @Override
  public void connect(final ServerInfo target, final ServerConnectEvent.Reason reason) {
    connect(target, null, reason);
  }

  @Override
  public void connect(final ServerInfo target, final Callback<Boolean> callback) {
    connect(target, callback, ServerConnectEvent.Reason.PLUGIN);
  }

  @Override
  public void connect(final ServerInfo target, final @Nullable Callback<Boolean> callback,
                      final ServerConnectEvent.Reason reason) {
    connect(target, callback, reason, true);
  }

  private void connect(final ServerInfo target, final @Nullable Callback<Boolean> callback,
                       final ServerConnectEvent.Reason reason, final boolean feedback) {
    final RegisteredServer server = layer.registered(target);
    nextConnectReason = reason == null ? ServerConnectEvent.Reason.PLUGIN : reason;
    final ConnectionRequestBuilder request = player.createConnectionRequest(server);
    if (callback == null) {
      if (feedback) {
        request.fireAndForget();
      } else {
        request.connect();
      }
      return;
    }
    request.connect().whenComplete((result, error) -> {
      if (error != null) {
        callback.done(false, error);
      } else {
        callback.done(result.isSuccessful(), null);
      }
    });
  }

  @Override
  public void connect(final ServerConnectRequest request) {
    final RegisteredServer server = layer.registered(request.getTarget());
    nextConnectReason = request.getReason() == null
        ? ServerConnectEvent.Reason.PLUGIN : request.getReason();
    final CompletableFuture<ConnectionRequestBuilder.Result> future =
        player.createConnectionRequest(server).connect();
    final CompletableFuture<ConnectionRequestBuilder.Result> timed = request.getConnectTimeout() > 0
        ? future.orTimeout(request.getConnectTimeout(), java.util.concurrent.TimeUnit.MILLISECONDS)
        : future;
    timed.whenComplete((result, error) -> {
      if (request.getCallback() == null) {
        return;
      }
      if (error != null) {
        request.getCallback().done(ServerConnectRequest.Result.FAIL, error);
        return;
      }
      request.getCallback().done(switch (result.getStatus()) {
        case SUCCESS -> ServerConnectRequest.Result.SUCCESS;
        case ALREADY_CONNECTED -> ServerConnectRequest.Result.ALREADY_CONNECTED;
        case CONNECTION_IN_PROGRESS -> ServerConnectRequest.Result.ALREADY_CONNECTING;
        case CONNECTION_CANCELLED -> ServerConnectRequest.Result.EVENT_CANCEL;
        default -> ServerConnectRequest.Result.FAIL;
      }, null);
    });
  }

  @Override
  public Server getServer() {
    return player.getCurrentServer().map(connection -> (Server) new BungeeServer(layer, connection))
        .orElse(null);
  }

  @Override
  public int getPing() {
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0, player.getPing()));
  }

  @Override
  public void sendData(final String channel, final byte[] data) {
    player.sendPluginMessage(Channels.identifier(channel), data);
  }

  @Override
  public PendingConnection getPendingConnection() {
    return pending;
  }

  @Override
  public void chat(final String message) {
    player.spoofChatInput(message);
  }

  @Override
  public ServerInfo getReconnectServer() {
    return reconnectServer;
  }

  @Override
  public void setReconnectServer(final ServerInfo server) {
    this.reconnectServer = server;
  }

  @Override
  @Deprecated
  public String getUUID() {
    return player.getUniqueId().toString().replace("-", "");
  }

  @Override
  public UUID getUniqueId() {
    return player.getUniqueId();
  }

  @Override
  public Locale getLocale() {
    final Locale locale = player.getEffectiveLocale();
    return locale != null ? locale : player.getPlayerSettings().getLocale();
  }

  @Override
  public byte getViewDistance() {
    return player.getPlayerSettings().getViewDistance();
  }

  @Override
  public ChatMode getChatMode() {
    return switch (player.getPlayerSettings().getChatMode()) {
      case COMMANDS_ONLY -> ChatMode.COMMANDS_ONLY;
      case HIDDEN -> ChatMode.HIDDEN;
      default -> ChatMode.SHOWN;
    };
  }

  @Override
  public boolean hasChatColors() {
    return player.getPlayerSettings().hasChatColors();
  }

  @Override
  public SkinConfiguration getSkinParts() {
    final com.velocitypowered.api.proxy.player.SkinParts parts =
        player.getPlayerSettings().getSkinParts();
    return new SkinConfiguration() {
      @Override
      public boolean hasCape() {
        return parts.hasCape();
      }

      @Override
      public boolean hasJacket() {
        return parts.hasJacket();
      }

      @Override
      public boolean hasLeftSleeve() {
        return parts.hasLeftSleeve();
      }

      @Override
      public boolean hasRightSleeve() {
        return parts.hasRightSleeve();
      }

      @Override
      public boolean hasLeftPants() {
        return parts.hasLeftPants();
      }

      @Override
      public boolean hasRightPants() {
        return parts.hasRightPants();
      }

      @Override
      public boolean hasHat() {
        return parts.hasHat();
      }
    };
  }

  @Override
  public MainHand getMainHand() {
    return player.getPlayerSettings().getMainHand() == PlayerSettings.MainHand.LEFT
        ? MainHand.LEFT : MainHand.RIGHT;
  }

  @Override
  public void setTabHeader(final BaseComponent header, final BaseComponent footer) {
    player.sendPlayerListHeaderAndFooter(Chat.toAdventure(header), Chat.toAdventure(footer));
  }

  @Override
  public void setTabHeader(final BaseComponent[] header, final BaseComponent[] footer) {
    player.sendPlayerListHeaderAndFooter(Chat.toAdventure(header), Chat.toAdventure(footer));
  }

  @Override
  public void resetTabHeader() {
    player.clearPlayerListHeaderAndFooter();
  }

  @Override
  public void sendTitle(final Title title) {
    if (title instanceof BungeeTitle bungeeTitle) {
      bungeeTitle.sendTo(player);
    } else {
      title.send(this);
    }
  }

  @Override
  public boolean isForgeUser() {
    return player.getModInfo().isPresent();
  }

  @Override
  public Map<String, String> getModList() {
    final Map<String, String> mods = new LinkedHashMap<>();
    player.getModInfo().ifPresent(info -> {
      for (final ModInfo.Mod mod : info.getMods()) {
        mods.put(mod.getId(), mod.getVersion());
      }
    });
    return mods;
  }

  @Override
  public Scoreboard getScoreboard() {
    // Like on BungeeCord, changing it sends nothing to the client. BungeeCord also copies the
    // backend's scoreboard into it, which PaperProxy does not.
    Unsupported.ignored("ProxiedPlayer.getScoreboard",
        "it does not contain the backend server's scoreboard");
    return scoreboard;
  }

  @Override
  public CompletableFuture<byte[]> retrieveCookie(final String cookie) {
    return layer.cookies().request(player, cookie);
  }

  @Override
  public void storeCookie(final String cookie, final byte[] data) {
    player.storeCookie(Key.key(cookie), data);
  }

  @Override
  public void transfer(final String host, final int port) {
    player.transferToHost(InetSocketAddress.createUnresolved(host, port));
  }

  @Override
  public String getClientBrand() {
    return player.getClientBrand();
  }

  @Override
  public void clearDialog() {
    Unsupported.ignored("ProxiedPlayer.clearDialog", "dialogs are not supported yet");
  }

  @Override
  public void showDialog(final Dialog dialog) {
    Unsupported.ignored("ProxiedPlayer.showDialog", "dialogs are not supported yet");
  }

  @Override
  public void sendServerLinks(final List<ServerLink> serverLinks) {
    player.setServerLinks(serverLinks.stream().map(BungeePlayer::toVelocity).toList());
  }

  private static com.velocitypowered.api.util.ServerLink toVelocity(final ServerLink link) {
    if (link.getType() == null) {
      return com.velocitypowered.api.util.ServerLink.serverLink(Chat.toAdventure(link.getLabel()),
          link.getUrl());
    }
    final com.velocitypowered.api.util.ServerLink.Type type = switch (link.getType()) {
      case REPORT_BUG -> com.velocitypowered.api.util.ServerLink.Type.BUG_REPORT;
      default -> com.velocitypowered.api.util.ServerLink.Type.valueOf(link.getType().name());
    };
    return com.velocitypowered.api.util.ServerLink.serverLink(type, link.getUrl());
  }

  @Override
  @Deprecated
  public InetSocketAddress getAddress() {
    return player.getRemoteAddress();
  }

  @Override
  public SocketAddress getSocketAddress() {
    return player.getRemoteAddress();
  }

  @Override
  @Deprecated
  public void disconnect(final String reason) {
    player.disconnect(Chat.fromLegacy(reason));
  }

  @Override
  public void disconnect(final BaseComponent... reason) {
    player.disconnect(Chat.toAdventure(reason));
  }

  @Override
  public void disconnect(final BaseComponent reason) {
    player.disconnect(Chat.toAdventure(reason));
  }

  @Override
  public boolean isConnected() {
    return player.isActive();
  }

  @Override
  public Unsafe unsafe() {
    return Unsupported.PACKETS;
  }

  @Override
  public String toString() {
    return "ProxiedPlayer(" + player.getUsername() + ")";
  }
}
