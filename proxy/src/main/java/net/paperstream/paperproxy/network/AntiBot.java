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

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
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
  private final Set<String> known = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean knownDirty = new AtomicBoolean();
  /** Timestamps of recent connection attempts, guarded by itself. */
  private final Deque<Long> recent = new ArrayDeque<>();
  private volatile long attackUntil;
  private volatile boolean attack;
  private volatile @Nullable Pattern blockedNames;
  private volatile String blockedSource = "";

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
          if (!line.isBlank()) {
            known.add(line.trim().toLowerCase(Locale.ROOT));
          }
        }
      }
    } catch (final IOException e) {
      logger.warn("Unable to read {}: {}", knownFile, e.getMessage());
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
    try {
      Files.write(knownFile, known.stream().sorted().toList(), StandardCharsets.UTF_8);
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
      }
    }
    if (attack && settings.attackKnownOnly() && !known.contains(name.toLowerCase(Locale.ROOT))) {
      event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
          Component.translatable("paperproxy.antibot.attack-kick")));
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
    if (known.add(event.getPlayer().getUsername().toLowerCase(Locale.ROOT))) {
      knownDirty.set(true);
    }
  }

  private void checkAttackEnd() {
    if (attack && System.currentTimeMillis() > attackUntil) {
      attack = false;
      logger.info("Bot attack over, everyone can join again.");
      notifyStaff("paperproxy.antibot.attack-end");
    }
  }

  private void notifyStaff(final String key) {
    final Component message = Component.translatable(key);
    server.getAllPlayers().stream().filter(p -> p.hasPermission(NOTIFY))
        .forEach(p -> p.sendMessage(message));
  }

  private @Nullable Pattern pattern(final String source) {
    if (source.isEmpty()) {
      return null;
    }
    if (!source.equals(blockedSource)) {
      blockedNames = Pattern.compile(source);
      blockedSource = source;
    }
    return blockedNames;
  }
}
