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

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.StateRegistry;
import io.netty.buffer.ByteBuf;
import java.lang.reflect.Method;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.ClearTitles;
import net.md_5.bungee.protocol.packet.Kick;
import net.md_5.bungee.protocol.packet.PlayerListHeaderFooter;
import net.md_5.bungee.protocol.packet.Subtitle;
import net.md_5.bungee.protocol.packet.SystemChat;
import net.md_5.bungee.protocol.packet.TitleTimes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lets BungeeCord plugins send packets with {@code unsafe().sendPacket}. Packets the Velocity
 * API covers (tab header, chat, titles, kicks) go through the API so Velocity keeps track of
 * them. Everything else (scoreboards, teams, tab list entries, boss bars) is encoded with
 * BungeeCord's own packet classes for the player's version and written to the connection.
 */
final class PacketBridge implements Connection.Unsafe {

  private static final Logger logger = LogManager.getLogger(PacketBridge.class);
  private static final Method GET_ID;

  static {
    Method method = null;
    try {
      method = Protocol.DirectionData.class.getDeclaredMethod("getId", Class.class, int.class);
      method.setAccessible(true);
    } catch (final ReflectiveOperationException | RuntimeException e) {
      logger.warn("BungeeCord packet ids are not available, unsafe().sendPacket will fail: {}",
          e.toString());
    }
    GET_ID = method;
  }

  private final Player player;

  PacketBridge(final Player player) {
    this.player = player;
  }

  @Override
  public void sendPacket(final DefinedPacket packet) {
    if (viaApi(packet)) {
      return;
    }
    if (!(player instanceof ConnectedPlayer connected)) {
      return;
    }
    final MinecraftConnection connection = connected.getConnection();
    if (connection.getState() != StateRegistry.PLAY || connection.isClosed()) {
      // During login or a server switch (configuration phase) play packets would break the
      // connection. BungeeCord drops them in that case too.
      return;
    }
    final int version = player.getProtocolVersion().getProtocol();
    final int id = packetId(packet, version);
    final ByteBuf buf = connection.getChannel().alloc().buffer();
    try {
      DefinedPacket.writeVarInt(id, buf);
      packet.write(buf, Protocol.GAME, ProtocolConstants.Direction.TO_CLIENT, version);
    } catch (final RuntimeException e) {
      buf.release();
      throw Unsupported.fail("Connection.unsafe().sendPacket",
          "encoding " + packet.getClass().getSimpleName() + " for protocol " + version
              + " failed: " + e.getMessage());
    }
    connection.write(buf);
  }

  @Override
  public void sendPacketQueued(final DefinedPacket packet) {
    sendPacket(packet);
  }

  private boolean viaApi(final DefinedPacket packet) {
    if (packet instanceof PlayerListHeaderFooter tab) {
      player.sendPlayerListHeaderAndFooter(Chat.toAdventure(tab.getHeader()),
          Chat.toAdventure(tab.getFooter()));
      return true;
    }
    if (packet instanceof SystemChat chat) {
      final Component message = Chat.toAdventure(chat.getMessage());
      if (chat.getPosition() == 2) {
        player.sendActionBar(message);
      } else {
        player.sendMessage(message);
      }
      return true;
    }
    if (packet instanceof Kick kick) {
      player.disconnect(Chat.toAdventure(kick.getMessage()));
      return true;
    }
    if (packet instanceof net.md_5.bungee.protocol.packet.Title title
        && title.getAction() == net.md_5.bungee.protocol.packet.Title.Action.TITLE) {
      player.sendTitlePart(TitlePart.TITLE, Chat.toAdventure(title.getText()));
      return true;
    }
    if (packet instanceof Subtitle subtitle) {
      player.sendTitlePart(TitlePart.SUBTITLE, Chat.toAdventure(subtitle.getText()));
      return true;
    }
    if (packet instanceof TitleTimes times) {
      player.sendTitlePart(TitlePart.TIMES, Title.Times.times(ticks(times.getFadeIn()),
          ticks(times.getStay()), ticks(times.getFadeOut())));
      return true;
    }
    if (packet instanceof ClearTitles clear) {
      if (clear.isReset()) {
        player.resetTitle();
      } else {
        player.clearTitle();
      }
      return true;
    }
    return false;
  }

  private static Duration ticks(final int ticks) {
    return Duration.ofMillis(ticks * 50L);
  }

  static int packetId(final DefinedPacket packet, final int version) {
    if (GET_ID == null) {
      throw Unsupported.fail("Connection.unsafe().sendPacket", "sending raw packets");
    }
    if (!ProtocolConstants.SUPPORTED_VERSION_IDS.contains(version)) {
      throw Unsupported.fail("Connection.unsafe().sendPacket",
          "sending raw packets to clients newer than the bundled BungeeCord protocol (" + version
              + ")");
    }
    try {
      return (int) GET_ID.invoke(Protocol.GAME.TO_CLIENT, packet.getClass(), version);
    } catch (final ReflectiveOperationException | RuntimeException e) {
      throw Unsupported.fail("Connection.unsafe().sendPacket",
          packet.getClass().getSimpleName() + " has no id for protocol " + version);
    }
  }
}
