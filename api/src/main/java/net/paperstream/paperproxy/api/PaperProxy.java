/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * The PaperProxy API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package net.paperstream.paperproxy.api;

import com.velocitypowered.api.proxy.Player;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;

/**
 * The PaperProxy extras for plugins. Everything else is the normal Velocity API.
 *
 * <pre>{@code
 * PaperProxy pp = PaperProxy.get();
 * pp.getClientVersion(player);                 // "1.21.4"
 * pp.getMessage("myplugin.welcome", "player", name);
 * pp.isOnline("survival");
 * pp.async(() -> database.save(player));
 * }</pre>
 */
public interface PaperProxy {

  /**
   * Returns the PaperProxy API.
   *
   * @return the API
   * @throws IllegalStateException if the proxy is not PaperProxy
   */
  static PaperProxy get() {
    return PaperProxyProvider.get();
  }

  /**
   * Tells whether the plugin runs on PaperProxy (and not on plain Velocity).
   *
   * @return true on PaperProxy
   */
  static boolean isAvailable() {
    return PaperProxyProvider.isSet();
  }

  /**
   * Returns the Minecraft version the player's client uses, e.g. {@code 1.21.4}.
   *
   * @param player the player
   * @return the version
   */
  String getClientVersion(Player player);

  /**
   * Returns a message from messages.yml. Plugins may add their own keys to messages.yml; the
   * placeholders are given as name/value pairs.
   *
   * @param key the key, e.g. {@code myplugin.welcome}
   * @param placeholders alternating names and values, e.g. {@code "player", "Steve"}
   * @return the message, rendered when it is sent to a player
   */
  Component getMessage(String key, String... placeholders);

  /**
   * Tells whether a backend server passed its last health check.
   *
   * @param server the server name
   * @return false only if the server is known to be offline
   */
  boolean isOnline(String server);

  /**
   * Tells whether the network or a server is in maintenance.
   *
   * @param server a server name, or null for the whole network
   * @return true if in maintenance
   */
  boolean isMaintenance(String server);

  /**
   * Returns the forwarding mode of a backend server.
   *
   * @param server the server name
   * @return modern, paperguard, bungeeguard, legacy or none
   */
  String getForwardingMode(String server);

  /**
   * Runs blocking work on a virtual thread, away from the network threads.
   *
   * @param task the work
   * @return completes when the work is done
   */
  CompletableFuture<Void> async(Runnable task);
}
