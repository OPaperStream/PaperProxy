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

import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Serves proxy statistics in the Prometheus text format at {@code /metrics}.
 */
public final class MetricsEndpoint {

  private static final Logger logger = LogManager.getLogger(MetricsEndpoint.class);

  private final VelocityServer server;
  private @Nullable HttpServer http;
  private PaperProxyConfig.@Nullable Metrics running;

  /**
   * Creates the endpoint.
   *
   * @param server the proxy
   */
  public MetricsEndpoint(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Starts, restarts or stops the endpoint to match the current settings.
   */
  public synchronized void apply() {
    final PaperProxyConfig.Metrics settings = server.getPaperProxyConfig().values().metrics();
    if (settings.equals(running) && (http != null) == settings.enabled()) {
      return;
    }
    stop();
    if (!settings.enabled()) {
      return;
    }
    try {
      final HttpServer created = HttpServer.create(
          new InetSocketAddress(settings.bind(), settings.port()), 0);
      created.createContext("/metrics", exchange -> {
        final byte[] body = render().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(body);
        }
      });
      created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      created.start();
      http = created;
      running = settings;
      logger.info("Metrics available at http://{}:{}/metrics", settings.bind(), settings.port());
    } catch (final IOException e) {
      logger.error("Unable to start the metrics endpoint on {}:{}: {}", settings.bind(),
          settings.port(), e.getMessage());
    }
  }

  /**
   * Stops the endpoint.
   */
  public synchronized void stop() {
    if (http != null) {
      http.stop(0);
      http = null;
    }
    running = null;
  }

  String render() {
    final StringBuilder out = new StringBuilder(1024);
    gauge(out, "paperproxy_players_online", "Players connected to the proxy",
        server.getPlayerCount());
    out.append("# HELP paperproxy_server_players Players on a backend server\n")
        .append("# TYPE paperproxy_server_players gauge\n");
    for (final RegisteredServer registered : server.getAllServers()) {
      out.append("paperproxy_server_players{server=\"").append(label(registered)).append("\"} ")
          .append(registered.getPlayersConnected().size()).append('\n');
    }
    out.append("# HELP paperproxy_server_up Whether the last health check reached the server\n")
        .append("# TYPE paperproxy_server_up gauge\n");
    for (final RegisteredServer registered : server.getAllServers()) {
      out.append("paperproxy_server_up{server=\"").append(label(registered)).append("\"} ")
          .append(server.getHealthChecker().isOnline(registered.getServerInfo().getName())
              ? 1 : 0).append('\n');
    }
    out.append("# HELP paperproxy_server_queue Players waiting for a server\n")
        .append("# TYPE paperproxy_server_queue gauge\n");
    for (final RegisteredServer registered : server.getAllServers()) {
      out.append("paperproxy_server_queue{server=\"").append(label(registered)).append("\"} ")
          .append(server.getServerQueue().size(registered.getServerInfo().getName()))
          .append('\n');
    }
    gauge(out, "paperproxy_antibot_attack", "1 while bot attack mode is on",
        server.getAntiBot().underAttack() ? 1 : 0);
    final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    gauge(out, "paperproxy_jvm_heap_used_bytes", "Used heap memory", heap.getUsed());
    gauge(out, "paperproxy_jvm_heap_max_bytes", "Maximum heap memory", heap.getMax());
    gauge(out, "paperproxy_jvm_threads", "Live threads",
        ManagementFactory.getThreadMXBean().getThreadCount());
    gauge(out, "paperproxy_uptime_seconds", "Seconds since the proxy started",
        ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    return out.toString();
  }

  private static void gauge(final StringBuilder out, final String name, final String help,
                            final long value) {
    out.append("# HELP ").append(name).append(' ').append(help).append('\n')
        .append("# TYPE ").append(name).append(" gauge\n")
        .append(name).append(' ').append(value).append('\n');
  }

  private static String label(final RegisteredServer registered) {
    return registered.getServerInfo().getName().replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
