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

import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.kyori.adventure.title.Title;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Restarts the proxy at configured times or on command, with a countdown in chat and a title
 * shortly before. Players go to shutdown.transfer-to when that is set.
 */
public final class RestartScheduler {

  private static final Logger logger = LogManager.getLogger(RestartScheduler.class);

  private final VelocityServer server;
  private volatile long restartAt;
  private volatile @Nullable ScheduledTask ticker;
  private volatile int lastWarned = -1;
  private volatile String reason = "";
  private volatile LocalDateTime lastPlanned = LocalDateTime.MIN;

  /**
   * Creates the scheduler.
   *
   * @param server the proxy
   */
  public RestartScheduler(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Starts watching the configured restart times.
   */
  public void start() {
    server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this::checkPlanned)
        .repeat(20, TimeUnit.SECONDS).schedule();
  }

  private void checkPlanned() {
    final var settings = server.getPaperProxyConfig().values().restart();
    if (restartAt != 0 || settings.times().isEmpty()) {
      return;
    }
    final int warn = settings.warnings().stream().mapToInt(Integer::intValue).max().orElse(60);
    final LocalDateTime now = LocalDateTime.now();
    for (final LocalTime time : settings.times()) {
      LocalDateTime at = now.toLocalDate().atTime(time);
      if (at.isBefore(now)) {
        at = at.plusDays(1);
      }
      final long seconds = Duration.between(now, at).getSeconds();
      if (seconds <= warn && !at.equals(lastPlanned)) {
        lastPlanned = at;
        schedule((int) seconds, "");
        return;
      }
    }
  }

  /**
   * Plans a restart.
   *
   * @param seconds seconds until the restart
   * @param reason shown to players, may be empty
   */
  public synchronized void schedule(final int seconds, final String reason) {
    cancel();
    this.restartAt = System.currentTimeMillis() + seconds * 1000L;
    this.reason = reason;
    this.lastWarned = -1;
    logger.info("Proxy restart in {} seconds", seconds);
    server.getDiscordWebhook().send(DiscordWebhook.Kind.RESTART,
        "Proxy restart in " + seconds + " seconds" + (reason.isEmpty() ? "" : ": " + reason));
    ticker = server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this::tick)
        .repeat(1, TimeUnit.SECONDS).schedule();
  }

  /**
   * Cancels a planned restart.
   *
   * @return true if one was planned
   */
  public synchronized boolean cancel() {
    final ScheduledTask current = ticker;
    if (current == null) {
      return false;
    }
    current.cancel();
    ticker = null;
    restartAt = 0;
    return true;
  }

  /**
   * Seconds left until a planned restart.
   *
   * @return the seconds, or -1 if none is planned
   */
  public int secondsLeft() {
    final long at = restartAt;
    return at == 0 ? -1 : (int) Math.max(0, (at - System.currentTimeMillis() + 999) / 1000);
  }

  private void tick() {
    final int left = secondsLeft();
    if (left < 0) {
      return;
    }
    if (left == 0) {
      cancel();
      logger.info("Restarting the proxy as planned");
      server.shutdown(true, Component.translatable("paperproxy.restart.kick"));
      return;
    }
    final List<Integer> warnings = server.getPaperProxyConfig().values().restart().warnings();
    if (warnings.contains(left) && left != lastWarned) {
      lastWarned = left;
      final Component message = Component.translatable(reason.isEmpty()
              ? "paperproxy.restart.warning" : "paperproxy.restart.warning-reason",
          Argument.string("time", format(left)), Argument.string("reason", reason));
      server.getAllPlayers().forEach(p -> p.sendMessage(message));
      server.getConsoleCommandSource().sendMessage(message);
    }
    if (left <= 10) {
      final Title title = Title.title(
          Component.translatable("paperproxy.restart.title"),
          Component.translatable("paperproxy.restart.subtitle",
              Argument.string("time", format(left))),
          Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ZERO));
      server.getAllPlayers().forEach(p -> p.showTitle(title));
    }
  }

  static String format(final int seconds) {
    if (seconds >= 3600 && seconds % 3600 == 0) {
      return seconds / 3600 + "h";
    }
    if (seconds >= 60 && seconds % 60 == 0) {
      return seconds / 60 + "m";
    }
    if (seconds > 60) {
      return seconds / 60 + "m " + seconds % 60 + "s";
    }
    return seconds + "s";
  }
}
