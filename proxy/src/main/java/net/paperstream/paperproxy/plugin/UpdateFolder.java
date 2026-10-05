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

package net.paperstream.paperproxy.plugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Applies jars from {@code plugins/update} before plugins load. A jar replaces the installed
 * jar with the same file name or the same plugin id, so updates can be dropped in while the
 * proxy runs and take effect on the next start.
 */
public final class UpdateFolder {

  private static final Logger logger = LogManager.getLogger(UpdateFolder.class);
  private static final Pattern YAML_NAME = Pattern.compile("(?m)^name:\\s*['\"]?([^'\"\\r\\n]+)");

  private UpdateFolder() {
  }

  /**
   * Moves every jar from the update folder into the plugin folder.
   *
   * @param plugins the plugin folder
   * @return how many jars were applied
   */
  public static int apply(final Path plugins) {
    final Path update = plugins.resolve("update");
    if (!Files.isDirectory(update)) {
      return 0;
    }
    int applied = 0;
    try (DirectoryStream<Path> jars = Files.newDirectoryStream(update, "*.jar")) {
      for (final Path jar : jars) {
        try {
          final @Nullable String id = pluginId(jar);
          final Path old = installed(plugins, jar.getFileName().toString(), id);
          if (old != null && !old.getFileName().equals(jar.getFileName())) {
            Files.delete(old);
          }
          Files.move(jar, plugins.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
          logger.info("Updated plugin {} from plugins/update{}", jar.getFileName(),
              old == null ? " (new install)" : " (replaced " + old.getFileName() + ")");
          applied++;
        } catch (final IOException e) {
          logger.error("Could not apply update {}: {}", jar.getFileName(), e.getMessage());
        }
      }
    } catch (final IOException e) {
      logger.error("Could not read plugins/update: {}", e.getMessage());
    }
    return applied;
  }

  private static @Nullable Path installed(final Path plugins, final String fileName,
                                          final @Nullable String id) throws IOException {
    final Path same = plugins.resolve(fileName);
    if (Files.isRegularFile(same)) {
      return same;
    }
    if (id == null) {
      return null;
    }
    try (DirectoryStream<Path> jars = Files.newDirectoryStream(plugins, "*.jar")) {
      for (final Path jar : jars) {
        if (id.equals(pluginId(jar))) {
          return jar;
        }
      }
    }
    return null;
  }

  /**
   * Reads the plugin id of a Velocity or BungeeCord plugin jar.
   *
   * @param jar the jar
   * @return the lower case id, or null if the jar has no known descriptor
   */
  static @Nullable String pluginId(final Path jar) {
    try (JarFile file = new JarFile(jar.toFile())) {
      final ZipEntry velocity = file.getEntry("velocity-plugin.json");
      if (velocity != null) {
        try (InputStream in = file.getInputStream(velocity)) {
          final JsonObject json = JsonParser.parseReader(
              new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
          return json.has("id") ? "velocity:" + json.get("id").getAsString()
              .toLowerCase(Locale.ROOT) : null;
        }
      }
      for (final String name : new String[] {"bungee.yml", "plugin.yml"}) {
        final ZipEntry entry = file.getEntry(name);
        if (entry != null) {
          try (InputStream in = file.getInputStream(entry)) {
            final Matcher m = YAML_NAME.matcher(new String(in.readAllBytes(),
                StandardCharsets.UTF_8));
            if (m.find()) {
              return "bungee:" + m.group(1).trim().toLowerCase(Locale.ROOT);
            }
          }
        }
      }
    } catch (final IOException | RuntimeException e) {
      return null;
    }
    return null;
  }
}
