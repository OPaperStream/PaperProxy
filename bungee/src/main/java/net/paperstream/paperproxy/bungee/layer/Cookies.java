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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.key.Key;

/**
 * Turns Velocity's cookie request/receive events into the futures the Bungee API returns.
 */
final class Cookies {

  private record Request(UUID player, Key key) {
  }

  private final Map<Request, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();

  CompletableFuture<byte[]> request(final Player player, final String cookie) {
    final Request request = new Request(player.getUniqueId(), Key.key(cookie));
    final CompletableFuture<byte[]> future = pending.computeIfAbsent(request, r -> {
      final CompletableFuture<byte[]> created = new CompletableFuture<>();
      created.orTimeout(30, TimeUnit.SECONDS)
          .whenComplete((data, error) -> pending.remove(r, created));
      return created;
    });
    player.requestCookie(request.key());
    return future;
  }

  /**
   * Completes a pending request.
   *
   * @param player the player
   * @param key the cookie key
   * @param data the cookie data, may be null
   * @return true if a Bungee plugin was waiting for this cookie
   */
  boolean complete(final Player player, final Key key, final byte[] data) {
    final CompletableFuture<byte[]> future = pending.remove(new Request(player.getUniqueId(), key));
    if (future == null) {
      return false;
    }
    future.complete(data);
    return true;
  }
}
