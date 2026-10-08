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

package net.paperstream.paperproxy.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PunishmentsTest {

  @Test
  void parsesDurations() {
    assertEquals(Duration.ofMinutes(30), Punishments.parseDuration("30m"));
    assertEquals(Duration.ofHours(36), Punishments.parseDuration("1d12h"));
    assertEquals(Duration.ofDays(14), Punishments.parseDuration("2W"));
    assertNull(Punishments.parseDuration("griefing"));
    assertNull(Punishments.parseDuration("10"));
    assertNull(Punishments.parseDuration("1dx"));
    assertNull(Punishments.parseDuration("0m"));
  }

  @Test
  void bansFollowTheIdNotTheName() {
    final java.util.UUID banned = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    final java.util.UUID newcomer = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002");
    final Punishments.Entry byUuid = new Punishments.Entry(Punishments.Type.BAN,
        banned.toString(), "griefer", "", "Console", 0, 0);
    org.junit.jupiter.api.Assertions.assertTrue(byUuid.matches(banned, "renamed"));
    org.junit.jupiter.api.Assertions.assertFalse(byUuid.matches(newcomer, "griefer"));
    // Banned offline by name: matches by name until the UUID is known.
    final Punishments.Entry byName = new Punishments.Entry(Punishments.Type.BAN, null,
        "griefer", "", "Console", 0, 0);
    org.junit.jupiter.api.Assertions.assertTrue(byName.matches(newcomer, "Griefer"));
  }
}
