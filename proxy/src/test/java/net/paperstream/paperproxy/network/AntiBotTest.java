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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.InetAddress;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AntiBotTest {

  @Test
  void ipv6AddressesAreGroupedByTheirSlash64() throws Exception {
    assertEquals(AntiBot.network(InetAddress.getByName("2a01:4f8:1:2::1")),
        AntiBot.network(InetAddress.getByName("2a01:4f8:1:2:ffff:ffff:ffff:ffff")));
    assertNotEquals(AntiBot.network(InetAddress.getByName("2a01:4f8:1:2::1")),
        AntiBot.network(InetAddress.getByName("2a01:4f8:1:3::1")));
    assertNotEquals(AntiBot.network(InetAddress.getByName("203.0.113.1")),
        AntiBot.network(InetAddress.getByName("203.0.113.2")));
  }

  @Test
  void knownPlayersAreBoundToTheirNetwork() throws Exception {
    final AntiBot antiBot = new AntiBot(null, Path.of("."));
    final String home = antiBot.networkHash(InetAddress.getByName("203.0.113.10"));
    assertEquals(home, antiBot.networkHash(InetAddress.getByName("203.0.113.77")));
    assertNotEquals(home, antiBot.networkHash(InetAddress.getByName("198.51.100.10")));
  }
}
