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

package net.paperstream.paperproxy.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageFormatterTest {

  @Test
  void positionalPlaceholdersBecomeArgumentTags() {
    assertEquals("Unable to connect to <arg:0>: <arg:1>",
        MessageFormatter.toMiniMessage("velocity.error.cant-connect",
            "Unable to connect to {server}: {reason}", ""));
  }

  @Test
  void positionalPlaceholdersCanBeReordered() {
    assertEquals("<arg:1> while joining <arg:0>",
        MessageFormatter.toMiniMessage("velocity.error.cant-connect",
            "{reason} while joining {server}", ""));
  }

  @Test
  void otherPlaceholdersBecomeNamedTags() {
    assertEquals("Based on Velocity <velocity_version>",
        MessageFormatter.toMiniMessage("paperproxy.command.based-on",
            "Based on Velocity {velocity_version}", ""));
  }

  @Test
  void prefixIsInserted() {
    assertEquals("<aqua>PP</aqua> Hello",
        MessageFormatter.toMiniMessage("any", "{prefix}Hello", "<aqua>PP</aqua> "));
  }

  @Test
  void legacyColorsResetFormatting() {
    assertEquals("<reset><red>Red <bold>bold",
        MessageFormatter.legacyToMiniMessage("&cRed &lbold"));
  }

  @Test
  void legacyCodesAreCaseInsensitive() {
    assertEquals("<reset><green>ok", MessageFormatter.legacyToMiniMessage("&Aok"));
  }

  @Test
  void legacyHexColors() {
    assertEquals("<reset><color:#55ffaa>hex", MessageFormatter.legacyToMiniMessage("&#55ffaahex"));
  }

  @Test
  void plainAmpersandsAreKept() {
    assertEquals("Tom & Jerry &z &#xyz", MessageFormatter.legacyToMiniMessage("Tom & Jerry &z &#xyz"));
    assertEquals("trailing &", MessageFormatter.legacyToMiniMessage("trailing &"));
  }

  @Test
  void mixedMiniMessageAndLegacy() {
    assertEquals("<gradient:red:blue>Hi</gradient> <reset><yellow>there",
        MessageFormatter.legacyToMiniMessage("<gradient:red:blue>Hi</gradient> &ethere"));
  }

  @Test
  void placeholdersAreListedWithoutPrefix() {
    assertEquals(Set.of("server", "reason"),
        MessageFormatter.placeholders("{prefix}{server} {reason} {server}"));
  }

  @Test
  void languageCandidates() {
    assertEquals(List.of("de_de", "de"), MessageFormatter.languageCandidates(Locale.GERMANY));
    assertEquals(List.of("de"), MessageFormatter.languageCandidates(Locale.GERMAN));
    assertEquals(List.of(), MessageFormatter.languageCandidates(Locale.ROOT));
  }
}
