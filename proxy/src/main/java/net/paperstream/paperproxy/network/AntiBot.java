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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Simple protection against bot floods: too many new connections per second switch on attack
 * mode, in which only players who joined before may connect. Also limits accounts per IP and
 * refuses names that match a pattern.
 */
public final class AntiBot {

  /** Permission that skips the per-IP limit. */
  public static final String BYPASS = "paperproxy.antibot.bypass";
  /** Permission to be told when attack mode starts and ends. */
  public static final String NOTIFY = "paperproxy.notify.antibot";

  private static final Logger logger = LogManager.getLogger(AntiBot.class);

  private final VelocityServer server;
  private final Path knownFile;

  /**
   * A player who joined before. The UUID and a salted hash of their network are only filled in
   * after a login with this version; older lists contain names only.
   *
   * @param uuid the UUID, empty if unknown
   * @param network salted hash of the address prefix, empty if unknown
   */
  record Known(String uuid, String network) {
  }

  private final Map<String, Known> known = new ConcurrentHashMap<>();
  private volatile String salt = "";
  private final AtomicBoolean knownDirty = new AtomicBoolean();
  /** Timestamps of recent connection attempts, guarded by itself. */
  private final Deque<Long> recent = new ArrayDeque<>();
  private volatile long attackUntil;
  private volatile boolean attack;

  /** Source and compiled form together, so a reload never pairs one with the other's twin. */
  private record NamePattern(String source, @Nullable Pattern pattern) {
  }

  private volatile NamePattern blockedNames = new NamePattern("", null);
  /**
   * Networks that looked at the server list in the last ten minutes. Bounded and expiring, so a
   * flood of addresses cannot make each ping slower.
   */
  private final Cache<String, Boolean> pings = Caffeine.newBuilder()
      .expireAfterWrite(10, TimeUnit.MINUTES)
      .maximumSize(200_000)
      .build();

  /**
   * Creates the protection.
   *
   * @param server the proxy
   * @param directory the proxy root, where known-players.txt is kept
   */
  public AntiBot(final VelocityServer server, final Path directory) {
    this.server = server;
    this.knownFile = directory.resolve("known-players.txt");
  }

  private PaperProxyConfig.AntiBot settings() {
    return server.getPaperProxyConfig().values().antiBot();
  }

  /**
   * Loads the known players and starts saving them regularly.
   */
  public void start() {
    try {
      if (Files.exists(knownFile)) {
        for (final String line : Files.readAllLines(knownFile, StandardCharsets.UTF_8)) {
          final String trimmed = line.trim();
          if (trimmed.startsWith("# salt ")) {
            salt = trimmed.substring(7).trim();
            continue;
          }
          if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            continue;
          }
          // "name" (old lists) or "name uuid network".
          final String[] parts = trimmed.split(" ");
          known.put(parts[0].toLowerCase(Locale.ROOT), new Known(
              parts.length > 1 ? parts[1] : "", parts.length > 2 ? parts[2] : ""));
        }
      }
    } catch (final IOException e) {
      logger.warn("Unable to read {}: {}", knownFile, e.getMessage());
    }
    if (salt.isEmpty()) {
      final byte[] random = new byte[16];
      new SecureRandom().nextBytes(random);
      salt = HexFormat.of().formatHex(random);
      knownDirty.set(true);
    }
    server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this::save)
        .repeat(1, TimeUnit.MINUTES).schedule();
    server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this::checkAttackEnd)
        .repeat(1, TimeUnit.SECONDS).schedule();
  }

  /**
   * Writes the known players if they changed.
   */
  public void save() {
    if (!knownDirty.getAndSet(false)) {
      return;
    }
    final List<String> lines = new ArrayList<>();
    lines.add("# Players who joined before. Format: name uuid network-hash");
    lines.add("# salt " + salt);
    new TreeMap<>(known).forEach((name, entry) -> lines.add(
        (name + " " + entry.uuid() + " " + entry.network()).trim()));
    try {
      // Write a temp file and move it in one step: a crash never leaves a half list behind.
      final Path temp = knownFile.resolveSibling(knownFile.getFileName() + ".tmp");
      Files.write(temp, lines, StandardCharsets.UTF_8);
      try {
        Files.move(temp, knownFile, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (final AtomicMoveNotSupportedException e) {
        Files.move(temp, knownFile, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (final IOException e) {
      knownDirty.set(true);
      logger.warn("Unable to write {}: {}", knownFile, e.getMessage());
    }
  }

  /**
   * Tells whether attack mode is on.
   *
   * @return true during an attack
   */
  public boolean underAttack() {
    return attack;
  }

  /**
   * Counts connections, switches attack mode on and refuses unknown or blocked names.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.FIRST)
  public void onPreLogin(final PreLoginEvent event) {
    final PaperProxyConfig.AntiBot settings = settings();
    if (!settings.enabled()) {
      return;
    }
    final String name = event.getUsername();
    final Pattern pattern = pattern(settings.blockedNamePattern());
    if (pattern != null && pattern.matcher(name).find()) {
      event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
          Component.translatable("paperproxy.antibot.blocked-name")));
      return;
    }
    final long now = System.currentTimeMillis();
    final int perSecond;
    synchronized (recent) {
      recent.addLast(now);
      while (!recent.isEmpty() && recent.peekFirst() < now - 1000) {
        recent.removeFirst();
      }
      perSecond = recent.size();
    }
    if (perSecond > settings.attackThreshold()) {
      attackUntil = now + TimeUnit.SECONDS.toMillis(settings.attackDurationSeconds());
      if (!attack) {
        attack = true;
        logger.warn("Bot attack detected ({} connections per second). Only known players "
            + "can join for now.", perSecond);
        notifyStaff("paperproxy.antibot.attack-start");
        server.getDiscordWebhook().send(DiscordWebhook.Kind.ANTIBOT,
            "paperproxy.discord.attack-start", "rate", String.valueOf(perSecond));
      }
    }
    // Names are not verified yet at this point, so a known name only counts from the network the
    // player used before. Entries from old lists without a network count by name.
    final InetAddress address = event.getConnection().getRemoteAddress().getAddress();
    final Known entry = known.get(name.toLowerCase(Locale.ROOT));
    final boolean isKnown = entry != null
        && (entry.network().isEmpty() || entry.network().equals(networkHash(address)));
    final String ping = settings.requirePing();
    if (!isKnown && (ping.equals("always") || (ping.equals("attack") && attack))) {
      if (pings.getIfPresent(network(address)) == null) {
        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
            Component.translatable("paperproxy.antibot.ping-first")));
        return;
      }
    }
    if (attack && settings.attackKnownOnly() && !isKnown) {
      event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
          Component.translatable("paperproxy.antibot.attack-kick")));
    }
  }

  /**
   * Remembers which addresses looked at the server list, bots usually skip that.
   *
   * @param event the event
   */
  @Subscribe
  public void onPing(final ProxyPingEvent event) {
    if (!settings().enabled() || settings().requirePing().equals("off")) {
      return;
    }
    pings.put(network(event.getConnection().getRemoteAddress().getAddress()), Boolean.TRUE);
  }

  /**
   * Groups addresses the way they are handed out: one IPv4 address, or one IPv6 /64, which a
   * single customer usually gets in full.
   *
   * @param address the address
   * @return the key
   */
  static String network(final InetAddress address) {
    final byte[] bytes = address.getAddress();
    if (bytes.length == 16) {
      return HexFormat.of().formatHex(bytes, 0, 8) + "::/64";
    }
    return address.getHostAddress();
  }

  /**
   * The network a known player is bound to: IPv4 /24 or IPv6 /64, salted and hashed so the file
   * does not hold addresses.
   *
   * @param address the address
   * @return the hash
   */
  String networkHash(final InetAddress address) {
    final byte[] bytes = address.getAddress();
    final int prefix = bytes.length == 16 ? 8 : 3;
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(salt.getBytes(StandardCharsets.UTF_8));
      digest.update(bytes, 0, prefix);
      return HexFormat.of().formatHex(digest.digest(), 0, 8);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Limits accounts per IP.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.EARLY)
  public void onLogin(final LoginEvent event) {
    final PaperProxyConfig.AntiBot settings = settings();
    final Player player = event.getPlayer();
    if (settings.enabled() && attack && settings.attackKnownOnly()) {
      // Now the UUID is verified: a known name with a different UUID is someone else.
      final Known entry = known.get(player.getUsername().toLowerCase(Locale.ROOT));
      if (entry != null && !entry.uuid().isEmpty()
          && !entry.uuid().equals(player.getUniqueId().toString())) {
        event.setResult(ResultedEvent.ComponentResult.denied(
            Component.translatable("paperproxy.antibot.attack-kick")));
        return;
      }
    }
    if (!settings.enabled() || settings.maxAccountsPerIp() <= 0
        || player.hasPermission(BYPASS)) {
      return;
    }
    final InetAddress address = player.getRemoteAddress().getAddress();
    final long sameIp = server.getAllPlayers().stream()
        .filter(other -> !other.equals(player))
        .filter(other -> other.getRemoteAddress().getAddress().equals(address))
        .count();
    if (sameIp >= settings.maxAccountsPerIp()) {
      event.setResult(ResultedEvent.ComponentResult.denied(
          Component.translatable("paperproxy.antibot.too-many-accounts",
              Argument.string("max", String.valueOf(settings.maxAccountsPerIp())))));
    }
  }

  /**
   * Remembers players who made it in.
   *
   * @param event the event
   */
  @Subscribe
  public void onPostLogin(final PostLoginEvent event) {
    final Player player = event.getPlayer();
    final Known entry = new Known(player.getUniqueId().toString(),
        networkHash(player.getRemoteAddress().getAddress()));
    if (!entry.equals(known.put(player.getUsername().toLowerCase(Locale.ROOT), entry))) {
      knownDirty.set(true);
    }
  }

  private void checkAttackEnd() {
    if (attack && System.currentTimeMillis() > attackUntil) {
      attack = false;
      logger.info("Bot attack over, everyone can join again.");
      notifyStaff("paperproxy.antibot.attack-end");
      server.getDiscordWebhook().send(DiscordWebhook.Kind.ANTIBOT,
          "paperproxy.discord.attack-end");
    }
  }

  private void notifyStaff(final String key) {
    final Component message = Component.translatable(key);
    server.getAllPlayers().stream().filter(p -> p.hasPermission(NOTIFY))
        .forEach(p -> p.sendMessage(message));
  }

  private @Nullable Pattern pattern(final String source) {
    final NamePattern current = blockedNames;
    if (current.source().equals(source)) {
      return current.pattern();
    }
    Pattern compiled = null;
    if (!source.isEmpty()) {
      try {
        compiled = Pattern.compile(source);
      } catch (final PatternSyntaxException e) {
        // The config check reports this; logins must never fail because of it.
        logger.warn("antibot.blocked-name-pattern is not a valid regular expression, ignoring it");
      }
    }
    blockedNames = new NamePattern(source, compiled);
    return compiled;
  }
}
