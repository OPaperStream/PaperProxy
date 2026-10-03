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

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.proxy.VelocityServer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import net.paperstream.paperproxy.config.VersionRange;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Applies maintenance mode, per-server version rules and health checks to logins, server
 * switches and the server list.
 */
public final class NetworkRules {

  /** Permission to join and switch during maintenance. */
  public static final String MAINTENANCE_BYPASS = "paperproxy.maintenance.bypass";

  private final VelocityServer server;

  /**
   * Creates the rules.
   *
   * @param server the proxy
   */
  public NetworkRules(final VelocityServer server) {
    this.server = server;
  }

  private PaperProxyConfig.Values values() {
    return server.getPaperProxyConfig().values();
  }

  /**
   * Global maintenance.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.EARLY)
  public void onLogin(final LoginEvent event) {
    if (!values().maintenance() || mayBypass(event.getPlayer())) {
      return;
    }
    event.setResult(ResultedEvent.ComponentResult.denied(
        Component.translatable("paperproxy.maintenance.kick")));
  }

  /**
   * Picks a usable first server when the configured one is offline, in maintenance or does not
   * support the player's version.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.LATE)
  public void onChooseInitialServer(final PlayerChooseInitialServerEvent event) {
    final Player player = event.getPlayer();
    final RegisteredServer chosen = event.getInitialServer().orElse(null);
    if (chosen != null && problem(player, chosen) == null) {
      return;
    }
    final List<String> order = server.getConfiguration().getAttemptConnectionOrder();
    for (final String name : order) {
      final Optional<RegisteredServer> candidate = server.getServer(name);
      if (candidate.isPresent() && problem(player, candidate.get()) == null) {
        event.setInitialServer(candidate.get());
        return;
      }
    }
    // Nothing better found: keep the original choice and let the connect check explain why.
  }

  /**
   * Refuses switches to servers the player cannot use, with a message instead of an error.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.EARLY)
  public void onServerPreConnect(final ServerPreConnectEvent event) {
    final RegisteredServer target = event.getResult().getServer().orElse(null);
    if (target == null) {
      return;
    }
    final Component problem = problem(event.getPlayer(), target);
    if (problem == null) {
      return;
    }
    event.setResult(ServerPreConnectEvent.ServerResult.denied());
    if (event.getPlayer().getCurrentServer().isPresent()) {
      event.getPlayer().sendMessage(problem);
    } else {
      event.getPlayer().disconnect(problem);
    }
  }

  /**
   * Shows maintenance in the server list.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.LATE)
  public void onPing(final ProxyPingEvent event) {
    if (!values().maintenance()) {
      return;
    }
    final ServerPing ping = event.getPing();
    final String label = LegacyComponentSerializer.legacySection().serialize(GlobalTranslator
        .render(Component.translatable("paperproxy.maintenance.version-label"), Locale.US));
    event.setPing(ping.asBuilder()
        // Server list responses are not translated by the proxy, so render them here.
        .description(GlobalTranslator.render(
            Component.translatable("paperproxy.maintenance.motd"), Locale.US))
        // An impossible protocol makes clients show the label in red instead of the player count.
        .version(new ServerPing.Version(-1, label))
        .build());
  }

  /**
   * Explains why a player cannot use a server.
   *
   * @param player the player
   * @param target the server
   * @return the message, or null if the player may connect
   */
  public @Nullable Component problem(final Player player, final RegisteredServer target) {
    final String name = target.getServerInfo().getName();
    final PaperProxyConfig.Values values = values();
    if (values.maintenanceServers().contains(name.toLowerCase(Locale.ROOT))
        && !mayBypass(player)) {
      return Component.translatable("paperproxy.maintenance.server",
          Argument.string("server", name));
    }
    final VersionRange range = values.versions().get(name.toLowerCase(Locale.ROOT));
    final ProtocolVersion version = player.getProtocolVersion();
    if (range != null && !range.contains(version)) {
      return Component.translatable("paperproxy.versions.denied",
          Argument.string("server", name),
          Argument.string("versions", range.text()),
          Argument.string("client_version", version.getMostRecentSupportedVersion()));
    }
    if (!server.getHealthChecker().isOnline(name)) {
      return Component.translatable("paperproxy.health.offline", Argument.string("server", name));
    }
    return null;
  }

  private boolean mayBypass(final Player player) {
    final java.util.Set<String> whitelist = values().maintenanceWhitelist();
    return player.hasPermission(MAINTENANCE_BYPASS)
        || whitelist.contains(player.getUsername().toLowerCase(Locale.ROOT))
        || whitelist.contains(player.getUniqueId().toString());
  }
}
