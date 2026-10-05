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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateFolderTest {

  @TempDir
  Path dir;

  private static void jar(final Path path, final String entry, final String content)
      throws IOException {
    try (OutputStream file = Files.newOutputStream(path);
         JarOutputStream out = new JarOutputStream(file)) {
      out.putNextEntry(new ZipEntry(entry));
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
  }

  @Test
  void replacesJarWithSamePluginId() throws IOException {
    final Path update = Files.createDirectories(dir.resolve("update"));
    jar(dir.resolve("LuckPerms-5.4.jar"), "bungee.yml", "name: LuckPerms\nmain: a.B\n");
    jar(dir.resolve("Other.jar"), "velocity-plugin.json", "{\"id\":\"other\"}");
    jar(update.resolve("LuckPerms-5.5.jar"), "bungee.yml", "name: 'LuckPerms'\nmain: a.B\n");

    assertEquals(1, UpdateFolder.apply(dir));
    assertFalse(Files.exists(dir.resolve("LuckPerms-5.4.jar")));
    assertTrue(Files.exists(dir.resolve("LuckPerms-5.5.jar")));
    assertTrue(Files.exists(dir.resolve("Other.jar")));
    assertFalse(Files.exists(update.resolve("LuckPerms-5.5.jar")));
  }

  @Test
  void readsVelocityId() throws IOException {
    jar(dir.resolve("a.jar"), "velocity-plugin.json", "{\"id\":\"MyPlugin\"}");
    assertEquals("velocity:myplugin", UpdateFolder.pluginId(dir.resolve("a.jar")));
  }
}
