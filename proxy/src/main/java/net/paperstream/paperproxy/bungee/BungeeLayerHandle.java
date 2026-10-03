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

package net.paperstream.paperproxy.bungee;

import java.util.List;

/**
 * The BungeeCord compatibility layer as seen from the proxy core. The implementation lives in its
 * own class loader so Velocity plugins never see BungeeCord classes.
 */
public interface BungeeLayerHandle {

  /**
   * Loads and enables all BungeeCord plugins ({@code onLoad}, then {@code onEnable}).
   */
  void enable();

  /**
   * Disables all BungeeCord plugins and stops their tasks.
   */
  void shutdown();

  /**
   * Returns the names of the loaded BungeeCord plugins.
   *
   * @return the plugin names
   */
  List<String> pluginNames();

  /**
   * Finds a loaded BungeeCord plugin by name, ignoring case.
   *
   * @param name the plugin name
   * @return the exact name, or empty
   */
  java.util.Optional<String> findPlugin(String name);

  /**
   * Returns the jar a loaded plugin came from.
   *
   * @param name the exact plugin name
   * @return the jar
   */
  java.nio.file.Path pluginFile(String name);

  /**
   * Disables and removes one plugin: listeners, commands, tasks and its class loader.
   *
   * @param name the exact plugin name
   * @return the plugin's former class loader, to check for leaks
   */
  ClassLoader unloadPlugin(String name);

  /**
   * Loads and enables one plugin jar.
   *
   * @param jar the jar
   * @return the plugin name
   * @throws Exception if it cannot be loaded
   */
  String loadPlugin(java.nio.file.Path jar) throws Exception;
}
