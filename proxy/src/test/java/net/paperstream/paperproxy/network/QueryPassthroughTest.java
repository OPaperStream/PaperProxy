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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.velocitypowered.api.proxy.server.QueryResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueryPassthroughTest {

  @Test
  void parsesFullStatAnswer() throws Exception {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(new byte[16]);
    for (final String s : List.of("hostname", "A Server", "version", "1.21.4", "plugins",
        "Paper on 1.21.4: LuckPerms 5.5; WorldEdit 7.3", "map", "world")) {
      out.write(s.getBytes(StandardCharsets.ISO_8859_1));
      out.write(0);
    }
    out.write(0);
    final Map<String, String> data = QueryPassthrough.parseFull(out.toByteArray());
    assertEquals("world", data.get("map"));

    final QueryResponse response = QueryPassthrough.apply(QueryResponse.builder()
        .hostname("proxy").gameVersion("x").map("proxy").proxyVersion("Velocity")
        .currentPlayers(3).maxPlayers(10).proxyHost("0.0.0.0").proxyPort(25565).build(), data);
    assertEquals("world", response.getMap());
    assertEquals("1.21.4", response.getGameVersion());
    assertEquals("Paper on 1.21.4", response.getProxyVersion());
    assertEquals(2, response.getPlugins().size());
    assertEquals(3, response.getCurrentPlayers());
  }

  @Test
  void hidesPlainAndNamespacedCommands() {
    final Set<String> hidden = Set.of("plugins");
    assertTrue(HiddenCommands.isHidden(LiteralArgumentBuilder.literal("plugins").build(), hidden));
    assertTrue(HiddenCommands.isHidden(
        LiteralArgumentBuilder.literal("bukkit:plugins").build(), hidden));
    assertFalse(HiddenCommands.isHidden(LiteralArgumentBuilder.literal("pl").build(), hidden));
  }
}
