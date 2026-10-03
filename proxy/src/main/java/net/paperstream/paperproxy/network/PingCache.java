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

import com.velocitypowered.api.proxy.server.ServerPing;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Remembers finished server list responses for a few seconds, per client version and host the
 * client connected to. During ping floods the proxy then answers from memory instead of pinging
 * backends and firing plugin events for every request.
 */
public final class PingCache {

  private record Entry(ServerPing ping, long expiresAt) {
  }

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final IntSupplier seconds;

  /**
   * Creates the cache.
   *
   * @param seconds supplies the lifetime in seconds; 0 disables caching
   */
  public PingCache(final IntSupplier seconds) {
    this.seconds = seconds;
  }

  /**
   * Builds the cache key.
   *
   * @param protocol the client protocol
   * @param host the host the client connected to
   * @return the key
   */
  public static String key(final int protocol, final String host) {
    return protocol + "|" + host;
  }

  /**
   * Returns a fresh cached response.
   *
   * @param key the key
   * @return the response, or null
   */
  public @Nullable ServerPing get(final String key) {
    if (seconds.getAsInt() <= 0) {
      return null;
    }
    final Entry entry = entries.get(key);
    if (entry == null) {
      return null;
    }
    if (entry.expiresAt() < System.nanoTime()) {
      entries.remove(key, entry);
      return null;
    }
    return entry.ping();
  }

  /**
   * Stores a response.
   *
   * @param key the key
   * @param ping the response
   */
  public void put(final String key, final ServerPing ping) {
    final int lifetime = seconds.getAsInt();
    if (lifetime <= 0) {
      return;
    }
    if (entries.size() > 4096) {
      // Bounded: a flood with random host names must not fill the memory.
      entries.clear();
    }
    entries.put(key, new Entry(ping, System.nanoTime() + lifetime * 1_000_000_000L));
  }

  /**
   * Drops everything, e.g. after maintenance was toggled.
   */
  public void clear() {
    entries.clear();
  }
}
