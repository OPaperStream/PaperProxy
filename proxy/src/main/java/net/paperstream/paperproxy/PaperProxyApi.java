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

package net.paperstream.paperproxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.api.PaperProxy;
import net.paperstream.paperproxy.api.party.PartyManager;
import org.apache.logging.log4j.LogManager;

/**
 * Implements the public {@link PaperProxy} API.
 */
public final class PaperProxyApi implements PaperProxy {

  private final VelocityServer server;
  private final ExecutorService virtualThreads = Executors.newThreadPerTaskExecutor(
      Thread.ofVirtual().name("PaperProxy async ", 0).factory());

  /**
   * Creates the API.
   *
   * @param server the proxy
   */
  public PaperProxyApi(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Waits for running {@link #async(Runnable)} tasks when the proxy stops, so plugins that save
   * data on shutdown do not lose it.
   *
   * @param seconds how long to wait at most
   */
  public void shutdown(final int seconds) {
    virtualThreads.shutdown();
    try {
      if (!virtualThreads.awaitTermination(seconds, TimeUnit.SECONDS)) {
        LogManager.getLogger(PaperProxyApi.class).warn(
            "Async plugin tasks were still running after {} seconds", seconds);
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public String getClientVersion(final Player player) {
    return player.getProtocolVersion().getMostRecentSupportedVersion();
  }

  @Override
  public Component getMessage(final String key, final String... placeholders) {
    Objects.requireNonNull(key, "key");
    if (placeholders.length % 2 != 0) {
      throw new IllegalArgumentException("Placeholders must be name/value pairs");
    }
    for (int i = 0; i < placeholders.length; i++) {
      if (placeholders[i] == null) {
        throw new NullPointerException((i % 2 == 0 ? "Placeholder name" : "Value of placeholder "
            + placeholders[i - 1]) + " at position " + i + " is null");
      }
    }
    final ComponentLike[] arguments = new ComponentLike[placeholders.length / 2];
    for (int i = 0; i < placeholders.length; i += 2) {
      arguments[i / 2] = Argument.string(placeholders[i], placeholders[i + 1]);
    }
    return Component.translatable(key, arguments);
  }

  @Override
  public boolean isOnline(final String server) {
    return this.server.getHealthChecker().isOnline(server);
  }

  @Override
  public boolean isMaintenance(final String server) {
    final var values = this.server.getPaperProxyConfig().values();
    return server == null ? values.maintenance()
        : values.maintenanceServers().contains(server.toLowerCase(Locale.ROOT));
  }

  @Override
  public String getForwardingMode(final String server) {
    return this.server.getForwarding().modeFor(server).name().toLowerCase(Locale.ROOT);
  }

  @Override
  public PartyManager getParties() {
    return server.getParties();
  }

  @Override
  public CompletableFuture<Void> async(final Runnable task) {
    return CompletableFuture.runAsync(task, virtualThreads);
  }
}
