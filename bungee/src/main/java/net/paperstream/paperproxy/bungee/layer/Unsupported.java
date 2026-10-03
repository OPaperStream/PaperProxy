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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.md_5.bungee.api.connection.Connection;
import net.md_5.bungee.protocol.DefinedPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Features of BungeeCord's internals that PaperProxy cannot offer. Each is reported once with a
 * clear message instead of failing silently.
 */
final class Unsupported {

  private static final Logger logger = LogManager.getLogger(Unsupported.class);
  private static final Set<String> reported = ConcurrentHashMap.newKeySet();

  static final Connection.Unsafe PACKETS = new Connection.Unsafe() {
    @Override
    public void sendPacket(final DefinedPacket packet) {
      throw fail("Connection.unsafe().sendPacket", "sending raw BungeeCord packets");
    }

    @Override
    public void sendPacketQueued(final DefinedPacket packet) {
      throw fail("Connection.unsafe().sendPacketQueued", "sending raw BungeeCord packets");
    }
  };

  private Unsupported() {
    throw new AssertionError();
  }

  /**
   * Logs once and returns the exception to throw.
   *
   * @param method the API method
   * @param what what it would have done
   * @return the exception
   */
  static UnsupportedOperationException fail(final String method, final String what) {
    final String message = method + " is not supported by PaperProxy (" + what
        + "). The plugin uses BungeeCord internals.";
    if (reported.add(method)) {
      logger.warn(message);
    }
    return new UnsupportedOperationException(message);
  }

  /**
   * Logs once that a call has no effect on PaperProxy.
   *
   * @param method the API method
   * @param what what it would have done
   */
  static void ignored(final String method, final String what) {
    if (reported.add(method)) {
      logger.warn("{} has no effect on PaperProxy ({}).", method, what);
    }
  }
}
