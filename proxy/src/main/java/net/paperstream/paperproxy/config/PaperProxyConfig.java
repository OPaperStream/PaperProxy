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

package net.paperstream.paperproxy.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Reads {@code paperproxy.toml}. Every invalid value is reported with its name and what is
 * allowed, and falls back to the default instead of stopping the proxy.
 */
public final class PaperProxyConfig {

  private static final Logger logger = LogManager.getLogger(PaperProxyConfig.class);
  private static final String FILE = "paperproxy.toml";

  private final Path file;
  private volatile Values values = Values.defaults();

  /**
   * The parsed settings.
   *
   * @param forwarding forwarding mode per server (lower case names)
   * @param paperGuardMaxAgeSeconds signature lifetime
   * @param versions allowed client versions per server
   * @param healthCheck whether backends are pinged
   * @param healthCheckIntervalSeconds ping interval
   * @param healthNotifyPermission permission for status messages
   * @param maintenance global maintenance
   * @param maintenanceServers servers in maintenance (lower case)
   * @param maintenanceWhitelist names or UUIDs allowed during maintenance (lower case)
   * @param pingCacheSeconds ping cache lifetime
   * @param watchdogMillis watchdog threshold, 0 = off
   * @param reloadRequiresConfirm whether reload needs "confirm"
   * @param reloadBlocked plugin ids that are never reloaded
   * @param updateCheck whether updates are checked
   * @param updateChannel release, beta or alpha
   * @param autoUpdate whether verified updates are downloaded
   * @param autoUpdateAllowMajor whether major updates are installed automatically
   */
  public record Values(Map<String, PlayerInfoForwarding> forwarding, int paperGuardMaxAgeSeconds,
                       Map<String, VersionRange> versions, boolean healthCheck,
                       int healthCheckIntervalSeconds, String healthNotifyPermission,
                       boolean maintenance, Set<String> maintenanceServers,
                       Set<String> maintenanceWhitelist, int pingCacheSeconds, int watchdogMillis,
                       boolean reloadRequiresConfirm, Set<String> reloadBlocked,
                       boolean updateCheck, String updateChannel, boolean autoUpdate,
                       boolean autoUpdateAllowMajor) {

    static Values defaults() {
      return new Values(Map.of(), 10, Map.of(), true, 10, "paperproxy.notify.health", false,
          Set.of(), Set.of(), 5, 500, true,
          Set.of("viaversion", "viabackwards", "viarewind", "luckperms", "geyser", "floodgate"),
          true, "release", false, false);
    }
  }

  /**
   * Creates the config.
   *
   * @param directory the proxy root
   */
  public PaperProxyConfig(final Path directory) {
    this.file = directory.resolve(FILE);
  }

  /**
   * Returns the current settings.
   *
   * @return the settings
   */
  public Values values() {
    return values;
  }

  /**
   * Loads the file, creating it from the bundled default if needed.
   *
   * @return true if the file was read without errors
   */
  public boolean load() {
    try {
      if (!Files.exists(file)) {
        try (InputStream in = PaperProxyConfig.class.getResourceAsStream("/paperproxy/" + FILE)) {
          if (in == null) {
            throw new IOException("bundled " + FILE + " is missing");
          }
          Files.copy(in, file);
        }
      }
      try (CommentedFileConfig config = CommentedFileConfig.builder(file).preserveInsertionOrder()
          .build()) {
        config.load();
        final List<String> errors = new ArrayList<>();
        final Values parsed = parse(config, errors);
        for (final String error : errors) {
          logger.error("{}: {}", FILE, error);
        }
        this.values = parsed;
        return errors.isEmpty();
      }
    } catch (final Exception e) {
      logger.error("Unable to read {} ({}), keeping the previous settings", FILE, e.getMessage());
      return false;
    }
  }

  /**
   * Turns global or per-server maintenance on or off and saves the file.
   *
   * @param server the server, or null for global maintenance
   * @param enabled the new state
   * @throws IOException if the file cannot be written
   */
  public synchronized void setMaintenance(final @Nullable String server, final boolean enabled)
      throws IOException {
    try (CommentedFileConfig config = CommentedFileConfig.builder(file).preserveInsertionOrder()
        .build()) {
      config.load();
      if (server == null) {
        config.set("maintenance.enabled", enabled);
      } else {
        final Set<String> servers = new LinkedHashSet<>(stringList(config.get("maintenance.servers")));
        servers.removeIf(s -> s.equalsIgnoreCase(server));
        if (enabled) {
          servers.add(server);
        }
        config.set("maintenance.servers", new ArrayList<>(servers));
      }
      config.save();
    }
    load();
  }

  static Values parse(final Config config, final List<String> errors) {
    final Values d = Values.defaults();

    final Map<String, PlayerInfoForwarding> forwarding = new LinkedHashMap<>();
    final Object forwardingServers = config.get("forwarding.servers");
    if (forwardingServers instanceof Config servers) {
      for (final Config.Entry entry : servers.entrySet()) {
        final Object raw = entry.getValue();
        final String value = String.valueOf(raw).toUpperCase(Locale.ROOT);
        try {
          forwarding.put(entry.getKey().toLowerCase(Locale.ROOT),
              PlayerInfoForwarding.valueOf(value));
        } catch (final IllegalArgumentException e) {
          errors.add("forwarding.servers." + entry.getKey() + ": '" + raw
              + "' is not a forwarding mode (modern, paperguard, bungeeguard, legacy, none)");
        }
      }
    }

    final Map<String, VersionRange> versions = new LinkedHashMap<>();
    final Object versionSection = config.get("versions");
    if (versionSection instanceof Config section) {
      for (final Config.Entry entry : section.entrySet()) {
        final Object raw = entry.getValue();
        try {
          versions.put(entry.getKey().toLowerCase(Locale.ROOT),
              VersionRange.parse(String.valueOf(raw)));
        } catch (final IllegalArgumentException e) {
          errors.add("versions." + entry.getKey() + ": " + e.getMessage());
        }
      }
    }

    final String channel = string(config, "updates.channel", d.updateChannel(), errors)
        .toLowerCase(Locale.ROOT);
    if (!List.of("release", "beta", "alpha").contains(channel)) {
      errors.add("updates.channel: '" + channel + "' must be release, beta or alpha");
    }

    return new Values(Map.copyOf(forwarding),
        integer(config, "paperguard.max-age-seconds", d.paperGuardMaxAgeSeconds(), 1, 300, errors),
        Map.copyOf(versions),
        bool(config, "health-check.enabled", d.healthCheck(), errors),
        integer(config, "health-check.interval-seconds", d.healthCheckIntervalSeconds(), 1, 3600,
            errors),
        string(config, "health-check.notify-permission", d.healthNotifyPermission(), errors),
        bool(config, "maintenance.enabled", d.maintenance(), errors),
        lowerSet(config.get("maintenance.servers")),
        lowerSet(config.get("maintenance.whitelist")),
        integer(config, "ping-cache.seconds", d.pingCacheSeconds(), 0, 300, errors),
        integer(config, "watchdog.warn-after-ms", d.watchdogMillis(), 0, 600_000, errors),
        bool(config, "plugin-reload.require-confirm", d.reloadRequiresConfirm(), errors),
        config.get("plugin-reload.blocked") == null ? d.reloadBlocked()
            : lowerSet(config.get("plugin-reload.blocked")),
        bool(config, "updates.check", d.updateCheck(), errors),
        List.of("release", "beta", "alpha").contains(channel) ? channel : d.updateChannel(),
        bool(config, "auto-update.enabled", d.autoUpdate(), errors),
        bool(config, "auto-update.allow-major", d.autoUpdateAllowMajor(), errors));
  }

  private static int integer(final Config config, final String path, final int def,
                             final int min, final int max, final List<String> errors) {
    final Object value = config.get(path);
    if (value == null) {
      return def;
    }
    if (!(value instanceof Number number)) {
      errors.add(path + ": '" + value + "' must be a whole number");
      return def;
    }
    final long v = number.longValue();
    if (v < min || v > max) {
      errors.add(path + ": " + v + " must be between " + min + " and " + max);
      return def;
    }
    return (int) v;
  }

  private static boolean bool(final Config config, final String path, final boolean def,
                              final List<String> errors) {
    final Object value = config.get(path);
    if (value == null) {
      return def;
    }
    if (!(value instanceof Boolean b)) {
      errors.add(path + ": '" + value + "' must be true or false");
      return def;
    }
    return b;
  }

  private static String string(final Config config, final String path, final String def,
                               final List<String> errors) {
    final Object value = config.get(path);
    if (value == null) {
      return def;
    }
    if (!(value instanceof String s)) {
      errors.add(path + ": '" + value + "' must be text in quotes");
      return def;
    }
    return s;
  }

  private static List<String> stringList(final @Nullable Object value) {
    final List<String> out = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (final Object o : list) {
        out.add(String.valueOf(o));
      }
    }
    return out;
  }

  private static Set<String> lowerSet(final @Nullable Object value) {
    final Set<String> out = new LinkedHashSet<>();
    for (final String s : stringList(value)) {
      out.add(s.toLowerCase(Locale.ROOT));
    }
    return Set.copyOf(out);
  }
}
