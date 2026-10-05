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

package net.paperstream.paperproxy.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Connects several PaperProxy instances through Redis: network wide player count, /find,
 * /alert, a list of proxies, and no double logins across proxies.
 *
 * <p>Redis is only used from one background thread, so slow or missing Redis never blocks
 * logins for longer than one second.
 */
public final class NetworkSync {

  private static final Logger logger = LogManager.getLogger(NetworkSync.class);
  private static final String PREFIX = "paperproxy:";
  private static final String PLAYERS = PREFIX + "players";
  private static final String NAMES = PREFIX + "names";
  private static final String CHANNEL = PREFIX + "events";
  private static final String PROXIES = PREFIX + "proxies";
  private static final int HEARTBEAT_SECONDS = 10;
  private static final int PROXY_TTL_SECONDS = 30;

  /** Where a player is on the network. */
  public record Location(String name, String proxy, String server) {
  }

  private final VelocityServer server;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
    final Thread thread = new Thread(runnable, "PaperProxy Network Sync");
    thread.setDaemon(true);
    return thread;
  });
  private volatile PaperProxyConfig.@Nullable Sync settings;
  private volatile @Nullable RedisConnection connection;
  private volatile @Nullable RedisConnection subscriber;
  private volatile String proxyId = "";
  private volatile Map<String, Integer> proxies = Map.of();
  private volatile boolean running;
  private volatile long generation;
  private final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();

  /**
   * Creates the sync.
   *
   * @param server the proxy
   */
  public NetworkSync(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Starts, restarts or stops the sync to match the current settings.
   */
  public synchronized void apply() {
    final PaperProxyConfig.Sync wanted = server.getPaperProxyConfig().values().sync();
    if (wanted.equals(settings) && running == wanted.enabled()) {
      return;
    }
    stop();
    settings = wanted;
    if (!wanted.enabled()) {
      return;
    }
    proxyId = wanted.proxyId().isEmpty() ? defaultId() : wanted.proxyId();
    running = true;
    final long gen = ++generation;
    worker.execute(() -> connectLoop(gen));
    Thread.ofPlatform().daemon().name("PaperProxy Network Sync Subscriber")
        .start(() -> subscribeLoop(gen));
    logger.info("Network sync enabled as proxy '{}' (Redis {}:{})", proxyId, wanted.host(),
        wanted.port());
  }

  /**
   * Stops the sync and removes this proxy's players from Redis.
   */
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    generation++;
    final RedisConnection current = connection;
    final List<UUID> local = server.getAllPlayers().stream().map(Player::getUniqueId).toList();
    try {
      worker.submit(() -> {
        if (current != null) {
          try {
            for (final UUID uuid : local) {
              removePlayer(current, uuid);
            }
            current.call("DEL", PREFIX + "proxy:" + proxyId);
            current.call("SREM", PROXIES, proxyId);
          } catch (final IOException ignored) {
            // Redis is gone; the entries expire on their own.
          }
        }
      }).get(3, TimeUnit.SECONDS);
    } catch (final Exception ignored) {
      // Best effort on shutdown.
    }
    closeQuietly(connection);
    closeQuietly(subscriber);
    connection = null;
    subscriber = null;
    proxies = Map.of();
  }

  /**
   * Tells whether the sync is connected to Redis.
   *
   * @return true when connected
   */
  public boolean connected() {
    return running && connection != null;
  }

  /**
   * Returns this proxy's id.
   *
   * @return the id
   */
  public String proxyId() {
    return proxyId;
  }

  /**
   * Returns the live proxies and their player counts, as of the last heartbeat.
   *
   * @return proxy id to players
   */
  public Map<String, Integer> proxies() {
    return proxies;
  }

  /**
   * Returns the number of players on all live proxies.
   *
   * @return the network player count, or the local count when not connected
   */
  public int networkPlayerCount() {
    if (!connected() || proxies.isEmpty()) {
      return server.getPlayerCount();
    }
    int total = 0;
    for (final Map.Entry<String, Integer> entry : proxies.entrySet()) {
      total += entry.getKey().equals(proxyId) ? server.getPlayerCount() : entry.getValue();
    }
    return total;
  }

  /**
   * Finds a player on another proxy.
   *
   * @param name the player name
   * @return where the player is, empty if not online anywhere
   */
  public Optional<Location> find(final String name) {
    final RedisConnection current = connection;
    if (!connected() || current == null) {
      return Optional.empty();
    }
    try {
      return worker.submit(() -> {
        final Object uuid = current.call("HGET", NAMES, name.toLowerCase(Locale.ROOT));
        if (uuid == null) {
          return Optional.<Location>empty();
        }
        return location(current, String.valueOf(uuid));
      }).get(1, TimeUnit.SECONDS);
    } catch (final Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Shows a message on every other proxy (the local proxy shows it itself).
   *
   * @param message the message
   */
  public void broadcast(final Component message) {
    publish("alert", GsonComponentSerializer.gson().serialize(message));
  }

  /**
   * Sends a custom message to every other proxy.
   *
   * @param type the message type, see {@link #on(String, Consumer)}
   * @param data the payload
   */
  public void send(final String type, final String data) {
    publish(type, data);
  }

  /**
   * Handles a custom message type from other proxies.
   *
   * @param type the message type
   * @param handler called with the payload
   */
  public void on(final String type, final Consumer<String> handler) {
    handlers.put(type, handler);
  }

  private Optional<Location> location(final RedisConnection current, final String uuid)
      throws IOException {
    final Object raw = current.call("HGET", PLAYERS, uuid);
    if (raw == null) {
      return Optional.empty();
    }
    final JsonObject json = JsonParser.parseString(String.valueOf(raw)).getAsJsonObject();
    final String proxy = json.get("proxy").getAsString();
    if (!proxies.containsKey(proxy) && current.call("EXISTS", PREFIX + "proxy:" + proxy)
        instanceof Long exists && exists == 0) {
      // The proxy died without cleaning up.
      return Optional.empty();
    }
    return Optional.of(new Location(json.get("name").getAsString(), proxy,
        json.has("server") ? json.get("server").getAsString() : ""));
  }

  /**
   * Shows the network player count in the server list.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.LATE)
  public void onPing(final ProxyPingEvent event) {
    final PaperProxyConfig.Sync current = settings;
    if (current == null || !current.networkPlayerCount() || !connected()) {
      return;
    }
    final ServerPing ping = event.getPing();
    if (ping.getPlayers().isEmpty()) {
      return;
    }
    event.setPing(ping.asBuilder().onlinePlayers(networkPlayerCount()).build());
  }

  /**
   * A player logging in here is kicked on any other proxy, like a second login on one server.
   *
   * @param event the event
   * @return the task
   */
  @Subscribe(order = PostOrder.LAST)
  public @Nullable EventTask onLogin(final LoginEvent event) {
    if (!event.getResult().isAllowed() || !connected()) {
      return null;
    }
    final Player player = event.getPlayer();
    return EventTask.async(() -> {
      final RedisConnection current = connection;
      if (current == null) {
        return;
      }
      try {
        worker.submit(() -> {
          final Optional<Location> elsewhere = location(current,
              player.getUniqueId().toString());
          if (elsewhere.isPresent() && !elsewhere.get().proxy().equals(proxyId)) {
            publish(current, "kick", player.getUniqueId().toString());
          }
          writePlayer(current, player, "");
          return null;
        }).get(1, TimeUnit.SECONDS);
      } catch (final Exception e) {
        logger.debug("Network sync login update failed", e);
      }
    });
  }

  /**
   * Keeps the player's server up to date.
   *
   * @param event the event
   */
  @Subscribe
  public void onServerConnected(final ServerConnectedEvent event) {
    if (!connected()) {
      return;
    }
    final Player player = event.getPlayer();
    final String serverName = event.getServer().getServerInfo().getName();
    submit(current -> writePlayer(current, player, serverName));
  }

  /**
   * Removes players that leave.
   *
   * @param event the event
   */
  @Subscribe
  public void onDisconnect(final DisconnectEvent event) {
    if (!connected()) {
      return;
    }
    final UUID uuid = event.getPlayer().getUniqueId();
    submit(current -> {
      final Optional<Location> location = location(current, uuid.toString());
      // Only remove the entry if it is still ours; the player may already be on another proxy.
      if (location.isPresent() && location.get().proxy().equals(proxyId)) {
        removePlayer(current, uuid);
      }
    });
  }

  private void writePlayer(final RedisConnection current, final Player player,
                           final String serverName) throws IOException {
    final JsonObject json = new JsonObject();
    json.addProperty("name", player.getUsername());
    json.addProperty("proxy", proxyId);
    json.addProperty("server", serverName);
    current.call("HSET", PLAYERS, player.getUniqueId().toString(), json.toString());
    current.call("HSET", NAMES, player.getUsername().toLowerCase(Locale.ROOT),
        player.getUniqueId().toString());
  }

  private void removePlayer(final RedisConnection current, final UUID uuid) throws IOException {
    final Object raw = current.call("HGET", PLAYERS, uuid.toString());
    current.call("HDEL", PLAYERS, uuid.toString());
    if (raw != null) {
      final String name = JsonParser.parseString(String.valueOf(raw)).getAsJsonObject()
          .get("name").getAsString().toLowerCase(Locale.ROOT);
      current.call("HDEL", NAMES, name);
    }
  }

  private void heartbeat(final RedisConnection current) throws IOException {
    current.call("SET", PREFIX + "proxy:" + proxyId, String.valueOf(server.getPlayerCount()),
        "EX", String.valueOf(PROXY_TTL_SECONDS));
    current.call("SADD", PROXIES, proxyId);
    final Object members = current.call("SMEMBERS", PROXIES);
    final Map<String, Integer> found = new LinkedHashMap<>();
    if (members instanceof List<?> list) {
      for (final Object member : list) {
        final String id = String.valueOf(member);
        final Object count = current.call("GET", PREFIX + "proxy:" + id);
        if (count == null) {
          // Its heartbeat expired: the proxy is gone.
          current.call("SREM", PROXIES, id);
        } else {
          found.put(id, Integer.parseInt(String.valueOf(count)));
        }
      }
    }
    proxies = Map.copyOf(found);
  }

  private void connectLoop(final long gen) {
    if (gen != generation || !running) {
      return;
    }
    final PaperProxyConfig.Sync current = settings;
    if (current == null) {
      return;
    }
    try {
      if (connection == null) {
        connection = RedisConnection.open(current.host(), current.port(), current.password(),
            current.database(), 2000);
        logger.info("Connected to Redis for the network sync");
        // After a Redis restart our players are missing: write them again.
        for (final Player player : server.getAllPlayers()) {
          writePlayer(connection, player, player.getCurrentServer()
              .map(c -> c.getServerInfo().getName()).orElse(""));
        }
      }
      heartbeat(connection);
    } catch (final IOException e) {
      if (connection != null) {
        logger.warn("Lost the Redis connection for the network sync: {}", e.getMessage());
      } else {
        logger.warn("Unable to reach Redis at {}:{} for the network sync: {}", current.host(),
            current.port(), e.getMessage());
      }
      closeQuietly(connection);
      connection = null;
    }
    server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, () -> {
      if (gen == generation && running) {
        worker.execute(() -> connectLoop(gen));
      }
    }).delay(connection == null ? 5 : HEARTBEAT_SECONDS, TimeUnit.SECONDS).schedule();
  }

  private void subscribeLoop(final long gen) {
    while (gen == generation && running) {
      final PaperProxyConfig.Sync current = settings;
      if (current == null) {
        return;
      }
      try (RedisConnection sub = RedisConnection.open(current.host(), current.port(),
          current.password(), current.database(), 2000)) {
        subscriber = sub;
        sub.subscribe(this::onMessage, CHANNEL);
      } catch (final IOException e) {
        if (gen != generation || !running) {
          return;
        }
        try {
          Thread.sleep(5000);
        } catch (final InterruptedException interrupted) {
          return;
        }
      }
    }
  }

  private void onMessage(final String channel, final String message) {
    try {
      final JsonObject json = JsonParser.parseString(message).getAsJsonObject();
      if (proxyId.equals(json.get("from").getAsString())) {
        return;
      }
      final String type = json.get("type").getAsString();
      final String data = json.get("data").getAsString();
      switch (type) {
        case "alert" -> server.sendMessage(GsonComponentSerializer.gson().deserialize(data));
        case "kick" -> server.getPlayer(UUID.fromString(data)).ifPresent(player ->
            player.disconnect(Component.translatable(
                "paperproxy.sync.logged-in-elsewhere")));
        default -> {
          final Consumer<String> handler = handlers.get(type);
          if (handler != null) {
            handler.accept(data);
          } else {
            logger.debug("Unknown network sync message {}", type);
          }
        }
      }
    } catch (final RuntimeException e) {
      logger.warn("Ignoring a broken network sync message: {}", e.getMessage());
    }
  }

  private void publish(final String type, final String data) {
    submit(current -> publish(current, type, data));
  }

  private void publish(final RedisConnection current, final String type, final String data)
      throws IOException {
    final JsonObject json = new JsonObject();
    json.addProperty("from", proxyId);
    json.addProperty("type", type);
    json.addProperty("data", data);
    current.call("PUBLISH", CHANNEL, json.toString());
  }

  @FunctionalInterface
  private interface RedisTask {
    void run(RedisConnection connection) throws IOException;
  }

  private void submit(final RedisTask task) {
    worker.execute(() -> {
      final RedisConnection current = connection;
      if (current == null) {
        return;
      }
      try {
        task.run(current);
      } catch (final IOException e) {
        logger.debug("Network sync update failed", e);
      }
    });
  }

  private static void closeQuietly(final @Nullable RedisConnection redis) {
    if (redis != null) {
      try {
        redis.close();
      } catch (final IOException ignored) {
        // Closing anyway.
      }
    }
  }

  private String defaultId() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (final IOException e) {
      host = "proxy";
    }
    return host + ":" + server.getBoundAddress().getPort();
  }
}
