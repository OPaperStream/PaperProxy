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

import com.velocitypowered.proxy.VelocityServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import net.paperstream.paperproxy.libraries.Libraries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Finds BungeeCord plugins and starts the compatibility layer only when there is at least one.
 * Networks without Bungee plugins pay nothing for it.
 */
public final class BungeeLayerBootstrap {

  private static final Logger logger = LogManager.getLogger(BungeeLayerBootstrap.class);
  private static final String LAYER_RESOURCE = "/paperproxy/bungee-layer.jar";
  private static final String LIBRARIES_RESOURCE = "/paperproxy/bungee-libraries.list";
  private static final String LAYER_CLASS = "net.paperstream.paperproxy.bungee.layer.BungeeLayer";

  private BungeeLayerBootstrap() {
    throw new AssertionError();
  }

  /**
   * Tells whether a jar is a BungeeCord plugin. Jars that also carry a velocity-plugin.json are
   * Velocity plugins: universal jars always run in their Velocity form.
   *
   * @param jar the jar file
   * @return true for BungeeCord-only plugins
   */
  public static boolean isBungeePlugin(final Path jar) {
    try (JarFile file = new JarFile(jar.toFile())) {
      if (file.getEntry("velocity-plugin.json") != null) {
        return false;
      }
      return file.getEntry("bungee.yml") != null || file.getEntry("plugin.yml") != null;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Starts the layer if the plugins directory contains BungeeCord plugins.
   *
   * @param server the proxy
   * @param pluginsDirectory the plugins directory
   * @return the layer, or empty if there are no BungeeCord plugins
   */
  public static Optional<BungeeLayerHandle> start(final VelocityServer server,
                                                  final Path pluginsDirectory) {
    final List<Path> jars = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDirectory, "*.jar")) {
      for (final Path jar : stream) {
        if (Files.isRegularFile(jar) && isBungeePlugin(jar)) {
          jars.add(jar);
        }
      }
    } catch (IOException e) {
      logger.error("Unable to scan {} for BungeeCord plugins", pluginsDirectory, e);
      return Optional.empty();
    }
    if (jars.isEmpty()) {
      return Optional.empty();
    }

    logger.info("Found {} BungeeCord plugin(s), starting the BungeeCord compatibility layer",
        jars.size());
    try {
      final List<URL> urls = new ArrayList<>();
      // The layer jar comes first so its patched PluginClassloader wins over bungeecord-api's.
      urls.add(extractLayer().toUri().toURL());
      try (InputStream list = BungeeLayerBootstrap.class.getResourceAsStream(LIBRARIES_RESOURCE)) {
        // Small PaperProxy jar: the BungeeCord API and its libraries are downloaded now.
        // The -full jar has them inside the layer jar and no list.
        if (list != null) {
          for (final Path library : Libraries.ensure(list)) {
            urls.add(library.toUri().toURL());
          }
        }
      }
      // Deliberately not closed: the layer lives as long as the proxy.
      final URLClassLoader loader = new URLClassLoader("paperproxy-bungee",
          urls.toArray(new URL[0]), VelocityServer.class.getClassLoader());
      final Object layer = loader.loadClass(LAYER_CLASS)
          .getConstructor(VelocityServer.class, Path.class, List.class)
          .newInstance(server, pluginsDirectory, List.copyOf(jars));
      return Optional.of((BungeeLayerHandle) layer);
    } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError e) {
      logger.error("Unable to start the BungeeCord compatibility layer; BungeeCord plugins "
          + "will not be loaded", e);
      return Optional.empty();
    }
  }

  private static Path extractLayer() throws IOException {
    final Path directory = Path.of(".paperproxy").toAbsolutePath();
    try {
      Files.createDirectories(directory);
    } catch (final IOException e) {
      throw new IOException("Cannot create " + directory + " (" + e + "). The proxy needs write "
          + "access to its own folder to unpack the BungeeCord layer.", e);
    }
    if (!Files.isWritable(directory)) {
      throw new IOException(directory + " is not writable. The proxy needs write access to its "
          + "own folder to unpack the BungeeCord layer.");
    }
    final Path target = directory.resolve("bungee-layer.jar");
    try (InputStream in = BungeeLayerBootstrap.class.getResourceAsStream(LAYER_RESOURCE)) {
      if (in == null) {
        throw new IOException("The BungeeCord layer is missing from this PaperProxy build");
      }
      Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }
}
