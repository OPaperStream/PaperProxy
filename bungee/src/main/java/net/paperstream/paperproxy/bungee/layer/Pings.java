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

import com.velocitypowered.api.util.Favicon;
import java.util.ArrayList;
import java.util.List;
import net.md_5.bungee.api.ServerPing;

/**
 * Converts server list pings between Velocity and BungeeCord.
 */
final class Pings {

  private Pings() {
    throw new AssertionError();
  }

  static ServerPing toBungee(final com.velocitypowered.api.proxy.server.ServerPing ping) {
    final ServerPing out = new ServerPing();
    out.setVersion(new ServerPing.Protocol(ping.getVersion().getName(),
        ping.getVersion().getProtocol()));
    ping.getPlayers().ifPresent(players -> {
      final List<ServerPing.PlayerInfo> sample = new ArrayList<>();
      players.getSample().forEach(p -> sample.add(new ServerPing.PlayerInfo(p.getName(), p.getId())));
      out.setPlayers(new ServerPing.Players(players.getMax(), players.getOnline(),
          sample.toArray(new ServerPing.PlayerInfo[0])));
    });
    out.setDescriptionComponent(Chat.toBungee(ping.getDescriptionComponent()));
    ping.getFavicon().ifPresent(favicon ->
        out.setFavicon(net.md_5.bungee.api.Favicon.create(favicon.getBase64Url())));
    return out;
  }

  static com.velocitypowered.api.proxy.server.ServerPing toVelocity(
      final ServerPing ping, final com.velocitypowered.api.proxy.server.ServerPing original) {
    final com.velocitypowered.api.proxy.server.ServerPing.Builder builder = original.asBuilder();
    if (ping.getVersion() != null) {
      builder.version(new com.velocitypowered.api.proxy.server.ServerPing.Version(
          ping.getVersion().getProtocol(), ping.getVersion().getName()));
    }
    if (ping.getPlayers() == null) {
      builder.nullPlayers();
    } else {
      builder.onlinePlayers(ping.getPlayers().getOnline());
      builder.maximumPlayers(ping.getPlayers().getMax());
      builder.clearSamplePlayers();
      if (ping.getPlayers().getSample() != null) {
        for (final ServerPing.PlayerInfo info : ping.getPlayers().getSample()) {
          builder.samplePlayers(new com.velocitypowered.api.proxy.server.ServerPing.SamplePlayer(
              info.getName(), info.getUniqueId()));
        }
      }
    }
    if (ping.getDescriptionComponent() != null) {
      builder.description(Chat.toAdventure(ping.getDescriptionComponent()));
    }
    if (ping.getFaviconObject() != null) {
      builder.favicon(new Favicon(ping.getFaviconObject().getEncoded()));
    } else {
      builder.clearFavicon();
    }
    return builder.build();
  }
}
