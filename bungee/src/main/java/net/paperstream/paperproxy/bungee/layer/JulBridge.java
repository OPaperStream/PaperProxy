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

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.apache.logging.log4j.LogManager;

/**
 * BungeeCord plugins log through java.util.logging. This sends those records to the proxy
 * console (Log4j) with the right level.
 */
final class JulBridge {

  private JulBridge() {
    throw new AssertionError();
  }

  /**
   * Creates the root logger handed to Bungee plugins as {@code ProxyServer#getLogger()}.
   *
   * @return the logger
   */
  static Logger createProxyLogger() {
    final Logger logger = new Logger("PaperProxy-Bungee", null) {
    };
    logger.setLevel(Level.ALL);
    logger.setUseParentHandlers(false);
    logger.addHandler(new Log4jHandler());
    return logger;
  }

  private static final class Log4jHandler extends Handler {

    private final org.apache.logging.log4j.Logger target = LogManager.getLogger("Bungee");
    private final SimpleFormatter formatter = new SimpleFormatter();

    @Override
    public void publish(final LogRecord record) {
      if (record == null) {
        return;
      }
      final String message = formatter.formatMessage(record);
      final int level = record.getLevel().intValue();
      final org.apache.logging.log4j.Level mapped;
      if (level >= Level.SEVERE.intValue()) {
        mapped = org.apache.logging.log4j.Level.ERROR;
      } else if (level >= Level.WARNING.intValue()) {
        mapped = org.apache.logging.log4j.Level.WARN;
      } else if (level >= Level.INFO.intValue()) {
        mapped = org.apache.logging.log4j.Level.INFO;
      } else if (level >= Level.FINE.intValue()) {
        mapped = org.apache.logging.log4j.Level.DEBUG;
      } else {
        mapped = org.apache.logging.log4j.Level.TRACE;
      }
      target.log(mapped, message, record.getThrown());
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  }
}
