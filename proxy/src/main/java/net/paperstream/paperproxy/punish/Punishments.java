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

package net.paperstream.paperproxy.punish;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.network.DiscordWebhook;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Network wide bans and mutes, kept in punishments.json and shared with other proxies through
 * the network sync.
 */
public final class Punishments {

  /** Kind of punishment. */
  public enum Type {
    /** Cannot join. */
    BAN,
    /** Cannot chat. */
    MUTE
  }

  /**
   * One ban or mute.
   *
   * @param type ban or mute
   * @param uuid the player's UUID, null if unknown when banned offline
   * @param name the player name, lower case
   * @param reason the reason, may be empty
   * @param by who issued it
   * @param created unix millis
   * @param until unix millis, 0 = permanent
   */
  public record Entry(Type type, @Nullable String uuid, String name, String reason, String by,
                      long created, long until) {

    boolean active(final long now) {
      return until == 0 || until > now;
    }

    boolean matches(final @Nullable UUID id, final String player) {
      // With a UUID on both sides only the UUID counts: someone who later takes a freed name
      // must not inherit an old ban. Entries made offline by name match by name until the
      // player's UUID is learned on their next login attempt.
      if (uuid != null && id != null) {
        return uuid.equals(id.toString());
      }
      return name.equalsIgnoreCase(player);
    }
  }

  private static final Logger logger = LogManager.getLogger(Punishments.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final Pattern DURATION = Pattern.compile("(\\d+)([smhdwy])");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
      .withZone(ZoneId.systemDefault());
  /** Commands a muted player may not use either. */
  private static final Set<String> CHAT_COMMANDS = Set.of("msg", "tell", "w", "whisper", "r",
      "reply", "me", "say", "minecraft:msg", "minecraft:tell", "minecraft:w", "minecraft:me");

  private final VelocityServer server;
  private final Path file;
  private final List<Entry> entries = new CopyOnWriteArrayList<>();

  /**
   * Creates the store.
   *
   * @param server the proxy
   * @param directory the proxy root
   */
  public Punishments(final VelocityServer server, final Path directory) {
    this.server = server;
    this.file = directory.resolve("punishments.json");
  }

  /**
   * Loads punishments.json and listens to other proxies.
   */
  public void start() {
    if (Files.exists(file)) {
      try {
        final List<Entry> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
            new TypeToken<List<Entry>>() { }.getType());
        if (loaded != null) {
          entries.addAll(loaded);
        }
      } catch (final IOException | JsonParseException e) {
        logger.error("Unable to read punishments.json, starting without bans: {}",
            e.getMessage());
      }
    }
    server.getNetworkSync().on("punish", data -> {
      final Entry entry = GSON.fromJson(data, Entry.class);
      add(entry, false);
    });
    server.getNetworkSync().on("pardon", data -> {
      final String[] parts = data.split(":", 2);
      remove(Type.valueOf(parts[0]), parts[1], false);
    });
  }

  /**
   * Adds a ban or mute, kicks a banned player and tells the other proxies.
   *
   * @param entry the punishment
   */
  public void add(final Entry entry) {
    add(entry, true);
  }

  private void add(final Entry entry, final boolean local) {
    entries.removeIf(e -> e.type() == entry.type() && e.name().equalsIgnoreCase(entry.name()));
    entries.add(entry);
    save();
    final Optional<Player> online = server.getPlayer(entry.name());
    if (entry.type() == Type.BAN) {
      online.ifPresent(p -> p.disconnect(banScreen(entry)));
    } else {
      online.ifPresent(p -> p.sendMessage(muteMessage(entry)));
    }
    if (local) {
      server.getNetworkSync().send("punish", GSON.toJson(entry));
      server.getDiscordWebhook().send(DiscordWebhook.Kind.PUNISH,
          "paperproxy.discord." + (entry.type() == Type.BAN ? "ban" : "mute")
              + (entry.until() == 0 ? "-permanent" : "-temporary"),
          "player", entry.name(), "by", entry.by(),
          "until", entry.until() == 0 ? "" : DATE.format(Instant.ofEpochMilli(entry.until())),
          "reason", entry.reason().isEmpty() ? "-" : entry.reason());
    }
  }

  /**
   * Lifts a ban or mute.
   *
   * @param type ban or mute
   * @param name the player name
   * @return true if there was one
   */
  public boolean remove(final Type type, final String name) {
    return remove(type, name, true);
  }

  private boolean remove(final Type type, final String name, final boolean local) {
    final boolean removed = entries.removeIf(e -> e.type() == type
        && e.name().equalsIgnoreCase(name));
    if (removed) {
      save();
    }
    if (local) {
      server.getNetworkSync().send("pardon", type.name() + ":" + name);
    }
    return removed;
  }

  /**
   * Finds the active punishment of a player.
   *
   * @param type ban or mute
   * @param uuid the UUID, may be null
   * @param name the name
   * @return the punishment, empty if none
   */
  public Optional<Entry> active(final Type type, final @Nullable UUID uuid, final String name) {
    final long now = System.currentTimeMillis();
    return entries.stream().filter(e -> e.type() == type && e.active(now)
        && e.matches(uuid, name)).findFirst();
  }

  /**
   * Returns all active punishments of one type.
   *
   * @param type ban or mute
   * @return the entries
   */
  public List<Entry> list(final Type type) {
    final long now = System.currentTimeMillis();
    return entries.stream().filter(e -> e.type() == type && e.active(now)).toList();
  }

  /**
   * Refuses banned players.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.EARLY)
  public void onLogin(final LoginEvent event) {
    final Player player = event.getPlayer();
    active(Type.BAN, player.getUniqueId(), player.getUsername()).ifPresent(entry -> {
      if (entry.uuid() == null) {
        // Banned by name while offline: remember the UUID so a name change does not help.
        add(new Entry(entry.type(), player.getUniqueId().toString(), entry.name(),
            entry.reason(), entry.by(), entry.created(), entry.until()), false);
      }
      event.setResult(ResultedEvent.ComponentResult.denied(banScreen(entry)));
    });
  }

  /**
   * Blocks chat of muted players.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.EARLY)
  public void onChat(final PlayerChatEvent event) {
    final Player player = event.getPlayer();
    active(Type.MUTE, player.getUniqueId(), player.getUsername()).ifPresent(entry -> {
      event.setResult(PlayerChatEvent.ChatResult.denied());
      player.sendMessage(muteMessage(entry));
    });
  }

  /**
   * Blocks private messages of muted players.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.EARLY)
  public void onCommand(final CommandExecuteEvent event) {
    if (!(event.getCommandSource() instanceof Player player)) {
      return;
    }
    final String command = event.getCommand().split(" ", 2)[0].toLowerCase(Locale.ROOT);
    if (!CHAT_COMMANDS.contains(command)) {
      return;
    }
    active(Type.MUTE, player.getUniqueId(), player.getUsername()).ifPresent(entry -> {
      event.setResult(CommandExecuteEvent.CommandResult.denied());
      player.sendMessage(muteMessage(entry));
    });
  }

  private Component banScreen(final Entry entry) {
    return Component.translatable(entry.until() == 0 ? "paperproxy.punish.ban-screen"
            : "paperproxy.punish.tempban-screen",
        Argument.string("reason", entry.reason().isEmpty() ? "-" : entry.reason()),
        Argument.string("until", entry.until() == 0 ? "" : DATE.format(
            Instant.ofEpochMilli(entry.until()))),
        Argument.string("by", entry.by()));
  }

  private Component muteMessage(final Entry entry) {
    return Component.translatable(entry.until() == 0 ? "paperproxy.punish.muted"
            : "paperproxy.punish.tempmuted",
        Argument.string("reason", entry.reason().isEmpty() ? "-" : entry.reason()),
        Argument.string("until", entry.until() == 0 ? "" : DATE.format(
            Instant.ofEpochMilli(entry.until()))));
  }

  /**
   * Formats a date for messages.
   *
   * @param millis unix millis
   * @return the text
   */
  public static String date(final long millis) {
    return DATE.format(Instant.ofEpochMilli(millis));
  }

  /**
   * Parses durations like 30m, 1d12h or 2w.
   *
   * @param text the text
   * @return the duration, null if the text is no duration
   */
  public static @Nullable Duration parseDuration(final String text) {
    final String lower = text.toLowerCase(Locale.ROOT);
    final Matcher m = DURATION.matcher(lower);
    long seconds = 0;
    int end = 0;
    while (m.find()) {
      if (m.start() != end) {
        return null;
      }
      end = m.end();
      final long n = Long.parseLong(m.group(1));
      seconds += n * switch (m.group(2)) {
        case "s" -> 1;
        case "m" -> 60;
        case "h" -> 3600;
        case "d" -> 86_400;
        case "w" -> 604_800;
        default -> 31_536_000;
      };
    }
    return end == 0 || end != lower.length() || seconds <= 0 ? null : Duration.ofSeconds(seconds);
  }

  private synchronized void save() {
    final long now = System.currentTimeMillis();
    final List<Entry> keep = new ArrayList<>(entries.stream().filter(e -> e.active(now)).toList());
    try {
      final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, GSON.toJson(keep), StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (final IOException e) {
      logger.error("Unable to write punishments.json: {}", e.getMessage());
    }
  }
}
