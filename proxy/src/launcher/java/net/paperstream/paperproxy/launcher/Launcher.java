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

package net.paperstream.paperproxy.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small entry point of PaperProxy. The jar only carries PaperProxy's own code and a list of the
 * libraries it needs. On start the libraries are downloaded once into {@code libraries/},
 * every file is checked against its SHA-256, and PaperProxy is started from there.
 *
 * <p>Compiled for Java 8 on purpose, so a too old Java prints a clear message instead of a
 * class version error.
 */
public final class Launcher {

  private static final int REQUIRED_JAVA = 25;
  private static final String RESOURCES = "META-INF/paperproxy/";
  private static final String MAIN = "com.velocitypowered.proxy.Velocity";
  private static final String[] REPOSITORIES = {
      "https://repo.maven.apache.org/maven2/",
      "https://repo.papermc.io/repository/maven-public/"
  };

  private Launcher() {
  }

  /**
   * Starts PaperProxy.
   *
   * @param args command line arguments, passed on to the proxy
   * @throws Exception if PaperProxy cannot be started
   */
  public static void main(final String[] args) throws Exception {
    final int java = javaVersion();
    if (java < REQUIRED_JAVA) {
      System.err.println("PaperProxy needs Java " + REQUIRED_JAVA + " or newer, but this is Java "
          + java + ". Download it from https://adoptium.net/");
      System.exit(1);
      return;
    }

    final Path self = Paths.get(
        Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    // Lets the auto-updater replace this jar instead of the extracted core.
    System.setProperty("paperproxy.launcherJar", self.toString());
    installPendingUpdate(self);

    final Path libraries = Paths.get(System.getProperty("paperproxy.libraries", "libraries"));
    cleanUp(libraries);
    final List<URL> urls = new ArrayList<>();
    final Path core = extractCore(libraries);
    removeOldCores(core);
    urls.add(core.toUri().toURL());
    for (final Path library : downloadLibraries(libraries)) {
      urls.add(library.toUri().toURL());
    }

    final URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]),
        ClassLoader.getSystemClassLoader().getParent());
    Thread.currentThread().setContextClassLoader(loader);
    final Method main = loader.loadClass(MAIN).getMethod("main", String[].class);
    try {
      main.invoke(null, (Object) args);
    } catch (final InvocationTargetException e) {
      // Show the proxy's own error, not a reflection wrapper.
      final Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw (Error) cause;
    }
  }

  /** Jar the resources are read from: this jar, or a verified update waiting to be installed. */
  private static Path resourceJar;

  /**
   * Installs an update the auto-updater downloaded and verified. On Windows the running jar is
   * locked, so the update is started from the update folder until the jar can be replaced.
   */
  private static void installPendingUpdate(final Path self) {
    final Path pending = Paths.get(System.getProperty("paperproxy.updateDir", "update"))
        .resolve("paperproxy.jar");
    if (!Files.isRegularFile(pending)) {
      return;
    }
    try {
      Files.copy(self, self.resolveSibling("paperproxy-old.jar"),
          StandardCopyOption.REPLACE_EXISTING);
      Files.copy(pending, self, StandardCopyOption.REPLACE_EXISTING);
      Files.delete(pending);
      System.out.println("[PaperProxy] Installed the downloaded update. The previous version is "
          + "kept as paperproxy-old.jar.");
      resourceJar = self;
    } catch (final IOException e) {
      System.out.println("[PaperProxy] Could not replace " + self.getFileName() + " ("
          + e.getMessage() + "). Starting the update from " + pending + "; replace the jar by "
          + "hand when the proxy is stopped.");
      resourceJar = pending;
    }
  }

  /** Removes temporary files left behind when a previous start was killed. */
  private static void cleanUp(final Path libraries) {
    if (!Files.isDirectory(libraries)) {
      return;
    }
    try (java.util.stream.Stream<Path> files = Files.walk(libraries)) {
      files.filter(file -> {
        final String name = file.getFileName().toString();
        return name.endsWith(".tmp") && (name.startsWith("core") || name.startsWith("lib"));
      }).forEach(file -> {
        try {
          Files.deleteIfExists(file);
        } catch (final IOException ignored) {
          // Best effort.
        }
      });
    } catch (final IOException ignored) {
      // Best effort.
    }
  }

  /** Each update brings a new core jar; older ones are no longer needed. */
  private static void removeOldCores(final Path current) {
    try (java.util.stream.Stream<Path> files = Files.list(current.getParent())) {
      files.filter(file -> !file.equals(current)
              && file.getFileName().toString().startsWith("paperproxy-core-")
              && file.getFileName().toString().endsWith(".jar"))
          .forEach(file -> {
            try {
              Files.deleteIfExists(file);
            } catch (final IOException ignored) {
              // Still in use or locked: try again next time.
            }
          });
    } catch (final IOException ignored) {
      // Best effort.
    }
  }

  private static Path extractCore(final Path libraries) throws IOException {
    final byte[] core = readResource(RESOURCES + "core.jar");
    final Path target = libraries.resolve("net/paperstream/paperproxy-core")
        .resolve("paperproxy-core-" + sha256(core).substring(0, 16) + ".jar");
    if (!Files.exists(target)) {
      Files.createDirectories(target.getParent());
      final Path temp = Files.createTempFile(target.getParent(), "core", ".tmp");
      Files.write(temp, core);
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  private static List<Path> downloadLibraries(final Path libraries) throws Exception {
    final List<Library> list = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        open(RESOURCES + "libraries.list"), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          list.add(Library.parse(line));
        }
      }
    }

    // Hashing all libraries on every start is slow; files whose size and modification time did
    // not change since they were last verified are trusted.
    final Path cacheFile = libraries.resolve(".verified");
    final java.util.Map<String, String> verified = readCache(cacheFile);
    final java.util.Map<String, String> nowVerified = new java.util.TreeMap<>();
    final List<Library> missing = new ArrayList<>();
    for (final Library library : list) {
      final Path file = libraries.resolve(library.path());
      if (!Files.isRegularFile(file)) {
        missing.add(library);
        continue;
      }
      final String stamp = Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis()
          + ":" + library.sha256;
      if (stamp.equals(verified.get(library.path())) || library.sha256.equals(sha256(file))) {
        nowVerified.put(library.path(), stamp);
      } else {
        missing.add(library);
      }
    }
    if (!missing.isEmpty()) {
      System.out.println("[PaperProxy] Downloading " + missing.size() + " of " + list.size()
          + " libraries (first start or update)...");
      final AtomicLong bytes = new AtomicLong();
      final ExecutorService pool = Executors.newFixedThreadPool(8);
      try {
        final List<Future<?>> futures = new ArrayList<>();
        for (final Library library : missing) {
          futures.add(pool.submit(() -> {
            bytes.addAndGet(download(library, libraries.resolve(library.path())));
            return null;
          }));
        }
        for (final Future<?> future : futures) {
          future.get();
        }
      } finally {
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);
      }
      System.out.println("[PaperProxy] Downloaded " + (bytes.get() / 1024 / 1024)
          + " MB of libraries, all checksums verified.");
    }

    final List<Path> files = new ArrayList<>();
    for (final Library library : list) {
      final Path file = libraries.resolve(library.path());
      files.add(file);
      if (!nowVerified.containsKey(library.path())) {
        nowVerified.put(library.path(), Files.size(file) + ":"
            + Files.getLastModifiedTime(file).toMillis() + ":" + library.sha256);
      }
    }
    writeCache(cacheFile, nowVerified);
    return files;
  }

  private static java.util.Map<String, String> readCache(final Path file) {
    final java.util.Map<String, String> out = new java.util.HashMap<>();
    if (!Files.isRegularFile(file)) {
      return out;
    }
    try {
      for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        final int space = line.indexOf(' ');
        if (space > 0) {
          out.put(line.substring(0, space), line.substring(space + 1));
        }
      }
    } catch (final IOException ignored) {
      // A broken cache only means hashing again.
    }
    return out;
  }

  private static void writeCache(final Path file, final java.util.Map<String, String> entries) {
    final StringBuilder out = new StringBuilder();
    for (final java.util.Map.Entry<String, String> entry : entries.entrySet()) {
      out.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
    }
    try {
      Files.createDirectories(file.getParent());
      final Path temp = Files.createTempFile(file.getParent(), "lib", ".tmp");
      Files.write(temp, out.toString().getBytes(StandardCharsets.UTF_8));
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    } catch (final IOException ignored) {
      // Next start hashes again.
    }
  }

  private static long download(final Library library, final Path target) throws IOException {
    IOException last = null;
    for (final String repository : REPOSITORIES) {
      byte[] data = null;
      for (int attempt = 1; attempt <= 3 && data == null; attempt++) {
        try {
          data = fetch(repository + library.path());
        } catch (IOException e) {
          last = e;
          if (attempt < 3) {
            try {
              Thread.sleep(1000L * attempt);
            } catch (final InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IOException("Interrupted while downloading " + library.coordinates);
            }
          }
        }
      }
      if (data == null) {
        continue;
      }
      if (!library.sha256.equals(sha256(data))) {
        last = new IOException("Checksum mismatch for " + library.coordinates + " from "
            + repository + ", file rejected");
        continue;
      }
      Files.createDirectories(target.getParent());
      final Path temp = Files.createTempFile(target.getParent(), "lib", ".tmp");
      Files.write(temp, data);
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      return data.length;
    }
    throw new IOException("Could not download " + library.coordinates
        + ". Check the internet connection or use the -full jar.", last);
  }

  private static byte[] fetch(final String url) throws IOException {
    final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(15_000);
    connection.setReadTimeout(60_000);
    connection.setRequestProperty("User-Agent", "PaperProxy-Launcher");
    try {
      if (connection.getResponseCode() != 200) {
        throw new IOException("HTTP " + connection.getResponseCode() + " for " + url);
      }
      try (InputStream in = connection.getInputStream()) {
        return readAll(in);
      }
    } finally {
      connection.disconnect();
    }
  }

  private static InputStream open(final String resource) throws IOException {
    if (resourceJar != null) {
      final java.util.zip.ZipFile zip = new java.util.zip.ZipFile(resourceJar.toFile());
      final java.util.zip.ZipEntry entry = zip.getEntry(resource);
      if (entry == null) {
        zip.close();
        throw new IOException(resource + " is missing from " + resourceJar);
      }
      final InputStream raw = zip.getInputStream(entry);
      return new java.io.FilterInputStream(raw) {
        @Override
        public void close() throws IOException {
          super.close();
          zip.close();
        }
      };
    }
    final InputStream in = Launcher.class.getClassLoader().getResourceAsStream(resource);
    if (in == null) {
      throw new IOException(resource + " is missing from this PaperProxy jar");
    }
    return in;
  }

  private static byte[] readResource(final String resource) throws IOException {
    try (InputStream in = open(resource)) {
      return readAll(in);
    }
  }

  private static byte[] readAll(final InputStream in) throws IOException {
    final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    final byte[] buffer = new byte[65536];
    int read;
    while ((read = in.read(buffer)) > 0) {
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private static String sha256(final Path file) throws IOException {
    final MessageDigest digest = digest();
    try (InputStream in = Files.newInputStream(file)) {
      final byte[] buffer = new byte[65536];
      int read;
      while ((read = in.read(buffer)) > 0) {
        digest.update(buffer, 0, read);
      }
    }
    return hex(digest.digest());
  }

  private static String sha256(final byte[] data) {
    return hex(digest().digest(data));
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(final byte[] bytes) {
    final StringBuilder out = new StringBuilder(bytes.length * 2);
    for (final byte b : bytes) {
      out.append(String.format(Locale.ROOT, "%02x", b));
    }
    return out.toString();
  }

  private static int javaVersion() {
    final String version = System.getProperty("java.specification.version", "0");
    final String major = version.startsWith("1.") ? version.substring(2) : version;
    try {
      return Integer.parseInt(major.split("\\.")[0]);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** One line of libraries.list: {@code group:artifact:version:classifier:extension sha256}. */
  private static final class Library {
    final String coordinates;
    final String sha256;
    final String group;
    final String artifact;
    final String version;
    final String classifier;
    final String extension;

    private Library(final String coordinates, final String sha256) {
      this.coordinates = coordinates;
      this.sha256 = sha256;
      final String[] parts = coordinates.split(":", -1);
      this.group = parts[0];
      this.artifact = parts[1];
      this.version = parts[2];
      this.classifier = parts[3];
      this.extension = parts[4];
    }

    static Library parse(final String line) {
      final String[] parts = line.split(" ");
      return new Library(parts[0], parts[1].toLowerCase(Locale.ROOT));
    }

    String path() {
      return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-"
          + version + (classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
    }
  }
}
