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

package net.paperstream.paperproxy.bridge;

import com.destroystokyo.paper.event.player.PlayerHandshakeEvent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PaperProxy-Bridge: verifies PaperGuard logins and tells the proxy about this server.
 *
 * <p>Fail-closed: when the bridge is not configured correctly, nobody can join.
 */
public final class PaperProxyBridge extends JavaPlugin implements Listener {

  static final String CHANNEL = "paperproxy:bridge";
  private static final String REJECT = "Unable to verify your connection. Please join through "
      + "the server's proxy.";

  private volatile PaperGuardVerifier verifier;
  private volatile String setupProblem;
  private Set<String> allowedProxies = new HashSet<>();
  private Method originalSocketAddress;
  private final AtomicBoolean reported = new AtomicBoolean();

  @Override
  public void onEnable() {
    saveDefaultConfig();
    setupProblem = configure();
    if (setupProblem != null) {
      getLogger().severe("==============================================================");
      getLogger().severe("PaperGuard is NOT active: " + setupProblem);
      getLogger().severe("For safety every login is rejected until this is fixed.");
      getLogger().severe("==============================================================");
    } else {
      getLogger().info("PaperGuard active for server '" + getConfig().getString("server-name")
          + "'.");
    }
    try {
      originalSocketAddress = PlayerHandshakeEvent.class
          .getMethod("getOriginalSocketAddressHostname");
    } catch (final NoSuchMethodException e) {
      originalSocketAddress = null;
      if (!allowedProxies.isEmpty()) {
        getLogger().warning("allowed-proxy-addresses needs Paper 1.16.5 or newer and is ignored.");
      }
    }
    getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
    getServer().getPluginManager().registerEvents(this, this);
  }

  private String configure() {
    if (!Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false)) {
      return "settings.bungeecord is false in spigot.yml";
    }
    final String serverName = getConfig().getString("server-name", "").trim();
    if (serverName.isEmpty()) {
      return "server-name is empty in plugins/PaperProxy-Bridge/config.yml";
    }
    final byte[] key;
    try {
      key = Base64.getDecoder().decode(getConfig().getString("key", "").trim());
    } catch (final IllegalArgumentException e) {
      return "key is not valid Base64";
    }
    if (key.length != 32) {
      return "key is missing or incomplete; run 'paperproxy paperguard key " + serverName
          + "' on the proxy";
    }
    final long maxAge = Math.max(1, getConfig().getLong("max-age-seconds", 10));
    allowedProxies = new HashSet<>(getConfig().getStringList("allowed-proxy-addresses"));
    verifier = new PaperGuardVerifier(key, serverName, maxAge);
    return null;
  }

  /**
   * Verifies the forwarded handshake before Paper uses it.
   *
   * @param event the event
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onHandshake(final PlayerHandshakeEvent event) {
    // Handling the event (not cancelled) makes Paper use exactly what we set below.
    event.setCancelled(false);
    final PaperGuardVerifier current = verifier;
    if (current == null) {
      fail(event, "bridge not configured");
      return;
    }
    if (!allowedProxies.isEmpty() && originalSocketAddress != null) {
      try {
        final Object address = originalSocketAddress.invoke(event);
        if (!allowedProxies.contains(String.valueOf(address))) {
          fail(event, "connection from " + address + " which is not an allowed proxy address");
          return;
        }
      } catch (final ReflectiveOperationException e) {
        fail(event, "could not read the connection address");
        return;
      }
    }

    final String[] split = event.getOriginalHandshake().split("\0", -1);
    if (split.length != 4) {
      fail(event, "not forwarded by PaperProxy");
      return;
    }
    final List<PaperGuardVerifier.Property> properties = new ArrayList<>();
    final JsonArray kept = new JsonArray();
    try {
      final JsonElement parsed = new JsonParser().parse(split[3]);
      for (final JsonElement element : parsed.getAsJsonArray()) {
        final JsonObject object = element.getAsJsonObject();
        final String name = object.get("name").getAsString();
        final String value = object.get("value").getAsString();
        final String signature = object.has("signature") ? object.get("signature").getAsString()
            : "";
        properties.add(new PaperGuardVerifier.Property(name, value, signature));
        if (!PaperGuardVerifier.PROPERTY.equals(name)) {
          kept.add(object);
        }
      }
    } catch (final RuntimeException e) {
      fail(event, "malformed forwarding data");
      return;
    }

    final PaperGuardVerifier.Result result = current.verify(split[0], split[1], split[2],
        properties, System.currentTimeMillis() / 1000L);
    if (result != PaperGuardVerifier.Result.OK) {
      fail(event, "PaperGuard " + result.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
          + " (player IP " + split[1] + ")");
      return;
    }
    event.setServerHostname(split[0]);
    event.setSocketAddressHostname(split[1]);
    event.setUniqueId(uuid(split[2]));
    event.setPropertiesJson(new Gson().toJson(kept));
  }

  /**
   * Second safety net: if the handshake check could not run (misconfiguration), refuse logins.
   *
   * @param event the event
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
    if (verifier == null) {
      event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, REJECT);
    }
  }

  /**
   * Tells the proxy which bridge version and plugins run here, once per server start.
   *
   * @param event the event
   */
  @EventHandler
  public void onJoin(final PlayerJoinEvent event) {
    if (!reported.compareAndSet(false, true)) {
      return;
    }
    final Map<String, Object> report = new LinkedHashMap<>();
    report.put("bridge", getDescription().getVersion());
    report.put("server", getConfig().getString("server-name", ""));
    report.put("software", Bukkit.getName() + " " + Bukkit.getVersion());
    final List<String> plugins = new ArrayList<>();
    for (final Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
      plugins.add(plugin.getName());
    }
    report.put("plugins", plugins);
    try {
      event.getPlayer().sendPluginMessage(this, CHANNEL,
          new Gson().toJson(report).getBytes(StandardCharsets.UTF_8));
    } catch (final RuntimeException e) {
      reported.set(false);
      getLogger().log(Level.FINE, "Could not send the bridge report", e);
    }
  }

  private void fail(final PlayerHandshakeEvent event, final String reason) {
    getLogger().warning("Rejected a login: " + reason);
    event.setFailed(true);
    event.setFailMessage(REJECT);
  }

  private static UUID uuid(final String undashed) {
    return UUID.fromString(undashed.replaceFirst(
        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
        "$1-$2-$3-$4-$5"));
  }

  /**
   * For tests: whether the bridge is ready.
   *
   * @return the setup problem, or null
   */
  String setupProblem() {
    return setupProblem;
  }
}
