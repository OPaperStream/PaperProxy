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

import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.api.event.BackendHealthChangeEvent;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Pings every backend regularly. Offline servers are skipped when players join and refused with
 * a clear message; the team is told when a server goes down or comes back.
 */
public final class HealthChecker {

  /** Backend state. */
  public enum Status {
    UNKNOWN,
    ONLINE,
    OFFLINE
  }

  private static final Logger logger = LogManager.getLogger(HealthChecker.class);

  private final VelocityServer server;
  private final Map<String, Status> states = new ConcurrentHashMap<>();
  private final Map<String, Integer> maxPlayers = new ConcurrentHashMap<>();
  private final Map<String, Long> lastOffline = new ConcurrentHashMap<>();
  private volatile @Nullable ScheduledTask task;
  private volatile int interval;

  /**
   * Creates the checker.
   *
   * @param server the proxy
   */
  public HealthChecker(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Starts or restarts the checks with the current settings.
   */
  public synchronized void start() {
    final PaperProxyConfig.Values values = server.getPaperProxyConfig().values();
    final ScheduledTask current = task;
    if (current != null && values.healthCheck() && interval == values.healthCheckIntervalSeconds()) {
      return;
    }
    if (current != null) {
      current.cancel();
      task = null;
    }
    if (!values.healthCheck()) {
      states.clear();
      return;
    }
    interval = values.healthCheckIntervalSeconds();
    task = server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this::checkAll)
        .repeat(interval, TimeUnit.SECONDS).schedule();
  }

  /**
   * Returns a server's state. Servers not checked yet are UNKNOWN and treated as online.
   *
   * @param name the server name
   * @return the state
   */
  public Status status(final String name) {
    return states.getOrDefault(name.toLowerCase(Locale.ROOT), Status.UNKNOWN);
  }

  /**
   * Tells whether a server may be used.
   *
   * @param name the server name
   * @return false only if the last check failed
   */
  public boolean isOnline(final String name) {
    return status(name) != Status.OFFLINE;
  }

  /**
   * Returns the player limit a server reported in its last ping.
   *
   * @param name the server name
   * @return the limit, or -1 if unknown
   */
  public int maxPlayers(final String name) {
    return maxPlayers.getOrDefault(name.toLowerCase(Locale.ROOT), -1);
  }

  /**
   * Returns when a server was last seen offline.
   *
   * @param name the server name
   * @return unix millis, or 0 if never
   */
  public long lastOffline(final String name) {
    return lastOffline.getOrDefault(name.toLowerCase(Locale.ROOT), 0L);
  }

  /**
   * Pings every server now.
   */
  public void checkAll() {
    final PingOptions options = PingOptions.builder()
        .timeout(Math.max(1000, server.getConfiguration().getConnectTimeout()),
            TimeUnit.MILLISECONDS)
        .build();
    for (final RegisteredServer registered : server.getAllServers()) {
      final String name = registered.getServerInfo().getName();
      registered.ping(options).whenComplete((ping, error) -> {
        if (ping != null) {
          ping.getPlayers().ifPresent(players ->
              maxPlayers.put(name.toLowerCase(Locale.ROOT), players.getMax()));
        }
        update(name, error == null ? Status.ONLINE : Status.OFFLINE);
      });
    }
    states.keySet().removeIf(name -> server.getServer(name).isEmpty());
    maxPlayers.keySet().removeIf(name -> server.getServer(name).isEmpty());
  }

  private void update(final String name, final Status status) {
    if (status == Status.OFFLINE) {
      lastOffline.put(name.toLowerCase(Locale.ROOT), System.currentTimeMillis());
    }
    final Status previous = states.put(name.toLowerCase(Locale.ROOT), status);
    if (previous == null || previous == Status.UNKNOWN || previous == status) {
      if (previous == null && status == Status.OFFLINE) {
        logger.warn("Server {} is offline", name);
      }
      return;
    }
    server.getEventManager().fireAndForget(new BackendHealthChangeEvent(name,
        status == Status.ONLINE));
    final String key = status == Status.ONLINE ? "paperproxy.health.up" : "paperproxy.health.down";
    if (status == Status.ONLINE) {
      logger.info("Server {} is back online", name);
    } else {
      logger.warn("Server {} went offline", name);
    }
    server.getDiscordWebhook().send(DiscordWebhook.Kind.HEALTH, status == Status.ONLINE
        ? ":green_circle: Server **" + name + "** is back online"
        : ":red_circle: Server **" + name + "** went offline");
    final Component message = Component.translatable(key, Argument.string("server", name));
    final String permission = server.getPaperProxyConfig().values().healthNotifyPermission();
    server.getAllPlayers().stream()
        .filter(player -> player.hasPermission(permission))
        .forEach(player -> player.sendMessage(message));
  }
}
