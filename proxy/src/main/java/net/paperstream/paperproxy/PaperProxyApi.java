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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.api.PaperProxy;

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

  @Override
  public String getClientVersion(final Player player) {
    return player.getProtocolVersion().getMostRecentSupportedVersion();
  }

  @Override
  public Component getMessage(final String key, final String... placeholders) {
    if (placeholders.length % 2 != 0) {
      throw new IllegalArgumentException("Placeholders must be name/value pairs");
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
  public CompletableFuture<Void> async(final Runnable task) {
    return CompletableFuture.runAsync(task, virtualThreads);
  }
}
