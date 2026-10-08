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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parses the new paperproxy.toml sections: groups, queue, hub, antibot, metrics, shutdown.
 */
class PaperProxyConfigTest {

  private static PaperProxyConfig.Values parse(final String toml, final List<String> errors) {
    final Config config = new TomlParser().parse(toml);
    return PaperProxyConfig.parse(config, errors);
  }

  @Test
  void bundledFileHasNoErrors() throws IOException {
    final String toml;
    try (InputStream in = getClass().getResourceAsStream("/paperproxy/paperproxy.toml")) {
      toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    final List<String> errors = new ArrayList<>();
    final PaperProxyConfig.Values values = parse(toml, errors);
    assertEquals(List.of(), errors);
    assertTrue(values.queue().enabled());
    assertTrue(values.antiBot().enabled());
    assertFalse(values.metrics().enabled());
    assertEquals("", values.hubTarget());
    assertEquals("", values.transferOnShutdown());
  }

  @Test
  void readsGroupsQueueHubAndShutdown() {
    final List<String> errors = new ArrayList<>();
    final PaperProxyConfig.Values values = parse("""
        [groups]
        Lobby = ["Lobby-1", "lobby-2"]
        [queue]
        interval-seconds = 5
        timeout-minutes = 3
        rejoin-after-restart = false
        [hub-command]
        target = "Lobby"
        aliases = ["spawn"]
        [shutdown]
        transfer-to = "play2.example.net:25565"
        """, errors);
    assertEquals(List.of(), errors);
    assertEquals(List.of("lobby-1", "lobby-2"), values.groups().get("lobby"));
    assertEquals(5, values.queue().intervalSeconds());
    assertEquals(3, values.queue().timeoutMinutes());
    assertFalse(values.queue().rejoinAfterRestart());
    assertEquals("lobby", values.hubTarget());
    assertEquals(List.of("spawn"), values.hubAliases());
    assertEquals("play2.example.net:25565", values.transferOnShutdown());
  }

  @Test
  void reportsBadValuesAndKeepsDefaults() {
    final List<String> errors = new ArrayList<>();
    final PaperProxyConfig.Values values = parse("""
        [groups]
        lobby = "lobby-1"
        [queue]
        interval-seconds = 0
        [antibot]
        blocked-name-pattern = "([a-z"
        attack-threshold = -1
        [metrics]
        port = 70000
        """, errors);
    assertEquals(5, errors.size(), errors.toString());
    assertTrue(values.groups().isEmpty());
    assertEquals(2, values.queue().intervalSeconds());
    assertEquals("", values.antiBot().blockedNamePattern());
    assertEquals(30, values.antiBot().attackThreshold());
    assertEquals(9225, values.metrics().port());
  }

  @Test
  void secretsCanComeFromFiles(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
      throws IOException {
    final java.nio.file.Path file = dir.resolve("redis");
    java.nio.file.Files.writeString(file, "from-file\nignored\n");
    assertEquals("from-file", PaperProxyConfig.secret("file:" + file));
    assertEquals("plain", PaperProxyConfig.secret("plain"));
    assertEquals("", PaperProxyConfig.secret("${env:PAPERPROXY_SURELY_UNSET_123}"));
  }
}
