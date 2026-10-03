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
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.paperstream.paperproxy.messages.MessageFormatter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Server list entries from {@code motd.yml}: several MOTDs (random or in turn), hover lines and
 * an optional text instead of the player count. Runs first, so plugins can still change it.
 */
public final class Motd {

  private static final Logger logger = LogManager.getLogger(Motd.class);
  private static final UUID NIL = new UUID(0, 0);

  private record Settings(boolean enabled, boolean rotate, List<String> motds, List<String> hover,
                          String versionText) {
  }

  private final Path file;
  private final AtomicInteger next = new AtomicInteger();
  private volatile Settings settings = new Settings(false, false, List.of(), List.of(), "");

  /**
   * Creates the MOTD settings.
   *
   * @param directory the proxy root
   */
  public Motd(final Path directory) {
    this.file = directory.resolve("motd.yml");
  }

  /**
   * Loads motd.yml, creating it on first start. On errors the previous settings stay active.
   */
  public void load() {
    try {
      if (!Files.exists(file)) {
        try (InputStream in = Motd.class.getResourceAsStream("/paperproxy/motd.yml")) {
          if (in != null) {
            Files.copy(in, file);
          }
        }
      }
      final Object root = new Yaml(new SafeConstructor(new LoaderOptions()))
          .load(Files.readString(file, StandardCharsets.UTF_8));
      if (!(root instanceof Map<?, ?> map)) {
        throw new IOException("expected key/value pairs");
      }
      settings = new Settings(Boolean.TRUE.equals(map.get("enabled")),
          "rotate".equals(String.valueOf(map.get("mode"))), strings(map.get("motds")),
          strings(map.get("hover")),
          map.get("version-text") == null ? "" : String.valueOf(map.get("version-text")));
    } catch (Exception e) {
      logger.error("Unable to read motd.yml ({}), keeping the previous settings", e.getMessage());
    }
  }

  /**
   * Applies the settings to a server list response.
   *
   * @param event the event
   */
  @Subscribe(order = PostOrder.FIRST)
  public void onPing(final ProxyPingEvent event) {
    final Settings current = settings;
    if (!current.enabled()) {
      return;
    }
    final ServerPing ping = event.getPing();
    final int online = ping.getPlayers().map(ServerPing.Players::getOnline).orElse(0);
    final int max = ping.getPlayers().map(ServerPing.Players::getMax).orElse(0);
    final String version = event.getConnection().getProtocolVersion().isSupported()
        ? event.getConnection().getProtocolVersion().getMostRecentSupportedVersion() : "?";
    final ServerPing.Builder builder = ping.asBuilder();

    if (!current.motds().isEmpty()) {
      final int index = current.rotate()
          ? Math.floorMod(next.getAndIncrement(), current.motds().size())
          : ThreadLocalRandom.current().nextInt(current.motds().size());
      builder.description(render(current.motds().get(index), online, max, version));
    }
    if (!current.hover().isEmpty()) {
      builder.clearSamplePlayers();
      for (final String line : current.hover()) {
        builder.samplePlayers(new ServerPing.SamplePlayer(LegacyComponentSerializer.legacySection()
            .serialize(render(line, online, max, version)), NIL));
      }
    }
    if (!current.versionText().isEmpty()) {
      builder.version(new ServerPing.Version(-1, LegacyComponentSerializer.legacySection()
          .serialize(render(current.versionText(), online, max, version))));
    }
    event.setPing(builder.build());
  }

  private static Component render(final String text, final int online, final int max,
                                  final String version) {
    final String filled = text.replace("{online}", String.valueOf(online))
        .replace("{max}", String.valueOf(max))
        .replace("{version}", version);
    return MiniMessage.miniMessage().deserialize(MessageFormatter.legacyToMiniMessage(filled));
  }

  private static List<String> strings(final Object value) {
    final List<String> out = new ArrayList<>();
    if (value instanceof List<?> list) {
      for (final Object element : list) {
        out.add(String.valueOf(element));
      }
    }
    return List.copyOf(out);
  }
}
