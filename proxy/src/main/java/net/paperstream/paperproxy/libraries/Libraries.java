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

package net.paperstream.paperproxy.libraries;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Downloads libraries listed in a {@code libraries.list} resource into the shared
 * {@code libraries/} folder, checking every file against its SHA-256. Used for parts that are
 * only needed sometimes, such as the BungeeCord layer.
 */
public final class Libraries {

  private static final Logger logger = LogManager.getLogger(Libraries.class);
  private static final List<String> REPOSITORIES = List.of(
      "https://repo.maven.apache.org/maven2/",
      "https://repo.papermc.io/repository/maven-public/");

  private Libraries() {
    throw new AssertionError();
  }

  /**
   * Returns the libraries folder (the same one the launcher uses).
   *
   * @return the folder
   */
  public static Path directory() {
    return Path.of(System.getProperty("paperproxy.libraries", "libraries"));
  }

  /**
   * Makes sure every library of a list is present and valid.
   *
   * @param list the libraries.list content
   * @return the library files, in list order
   * @throws IOException if a library cannot be downloaded or verified
   */
  public static List<Path> ensure(final InputStream list) throws IOException {
    final List<String[]> entries = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(list,
        StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          entries.add(line.split(" "));
        }
      }
    }
    final List<Path> files = new ArrayList<>();
    final List<String[]> missing = new ArrayList<>();
    for (final String[] entry : entries) {
      final Path file = directory().resolve(path(entry[0]));
      files.add(file);
      if (!Files.isRegularFile(file) || !sha256(Files.readAllBytes(file)).equals(entry[1])) {
        missing.add(entry);
      }
    }
    if (missing.isEmpty()) {
      return files;
    }
    logger.info("Downloading {} libraries...", missing.size());
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      final List<Future<Void>> futures = new ArrayList<>();
      for (final String[] entry : missing) {
        futures.add(pool.submit(() -> {
          download(http, entry[0], entry[1].toLowerCase(Locale.ROOT));
          return null;
        }));
      }
      for (final Future<Void> future : futures) {
        future.get();
      }
    } catch (final Exception e) {
      throw new IOException("Library download failed: " + e.getMessage(), e);
    }
    logger.info("Libraries downloaded and verified.");
    return files;
  }

  private static void download(final HttpClient http, final String coordinates,
                               final String sha256) throws IOException, InterruptedException {
    final String path = path(coordinates);
    for (final String repository : REPOSITORIES) {
      final HttpResponse<byte[]> response = http.send(
          HttpRequest.newBuilder(URI.create(repository + path)).timeout(Duration.ofMinutes(1))
              .header("User-Agent", "PaperProxy").build(),
          HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200 || !sha256(response.body()).equals(sha256)) {
        continue;
      }
      final Path target = directory().resolve(path);
      Files.createDirectories(target.getParent());
      final Path temp = Files.createTempFile(target.getParent(), "lib", ".tmp");
      Files.write(temp, response.body());
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      return;
    }
    throw new IOException("Could not download or verify " + coordinates);
  }

  static String path(final String coordinates) {
    final String[] parts = coordinates.split(":", -1);
    return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-"
        + parts[2] + (parts[3].isEmpty() ? "" : "-" + parts[3]) + "." + parts[4];
  }

  private static String sha256(final byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
