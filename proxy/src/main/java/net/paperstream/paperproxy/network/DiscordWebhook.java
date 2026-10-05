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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.proxy.VelocityServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Posts network events (servers going down, bot attacks, maintenance, updates, restarts) to a
 * Discord channel through a webhook.
 */
public final class DiscordWebhook {

  /** Event names that can be switched on in the config. */
  public enum Kind {
    /** Proxy start and stop. */
    PROXY,
    /** Backend servers going offline or coming back. */
    HEALTH,
    /** Bot attack mode. */
    ANTIBOT,
    /** Maintenance switched on or off. */
    MAINTENANCE,
    /** A new PaperProxy version. */
    UPDATE,
    /** Planned restarts. */
    RESTART,
    /** Bans and mutes. */
    PUNISH
  }

  private static final Logger logger = LogManager.getLogger(DiscordWebhook.class);

  private final VelocityServer server;
  private volatile @Nullable HttpClient http;

  /**
   * Creates the webhook sender.
   *
   * @param server the proxy
   */
  public DiscordWebhook(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Sends a message if a webhook is set and the event kind is enabled. Never blocks.
   *
   * @param kind the event kind
   * @param text the message, Discord markdown allowed
   */
  public void send(final Kind kind, final String text) {
    final var settings = server.getPaperProxyConfig().values().discord();
    if (settings.webhookUrl().isEmpty() || !settings.events().contains(kind)) {
      return;
    }
    final JsonObject body = new JsonObject();
    body.addProperty("username", settings.username());
    body.addProperty("content", text.length() > 1900 ? text.substring(0, 1900) : text);
    final JsonObject mentions = new JsonObject();
    mentions.add("parse", new JsonArray());
    body.add("allowed_mentions", mentions);
    final HttpRequest request;
    try {
      request = HttpRequest.newBuilder(URI.create(settings.webhookUrl()))
          .timeout(Duration.ofSeconds(10))
          .header("Content-Type", "application/json")
          .header("User-Agent", "PaperProxy")
          .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
          .build();
    } catch (final IllegalArgumentException e) {
      logger.warn("discord.webhook-url is not a valid URL");
      return;
    }
    client().sendAsync(request, HttpResponse.BodyHandlers.discarding())
        .whenComplete((response, error) -> {
          if (error != null) {
            logger.warn("Could not reach the Discord webhook: {}", error.getMessage());
          } else if (response.statusCode() >= 300) {
            logger.warn("Discord webhook answered with HTTP {}", response.statusCode());
          }
        });
  }

  private HttpClient client() {
    HttpClient current = http;
    if (current == null) {
      current = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
      http = current;
    }
    return current;
  }
}
