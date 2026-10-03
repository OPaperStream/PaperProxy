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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesFileTest {

  private static final String TEMPLATE = """
      # header comment
      enabled: false
      prefix: "P "

      velocity:
        error:
          # kept comment
          one: "One"
          two: "Two {server}"
      paperproxy:
        three: "Three"
      """;

  @TempDir
  Path dir;

  @Test
  void createsMissingFileFromTemplate() throws Exception {
    final Path file = dir.resolve("messages.yml");
    final MessagesFile.Result result = MessagesFile.load(file, TEMPLATE);
    assertEquals(TEMPLATE, Files.readString(file));
    assertEquals("Two {server}", result.values().get("velocity.error.two"));
    assertNull(result.backup());
  }

  @Test
  void keepsCompleteUserFileUntouched() throws Exception {
    final Path file = dir.resolve("messages.yml");
    final String user = TEMPLATE.replace("\"One\"", "\"Eins\"") + "# my own comment\n";
    Files.writeString(file, user);
    final MessagesFile.Result result = MessagesFile.load(file, TEMPLATE);
    assertEquals(user, Files.readString(file));
    assertEquals("Eins", result.values().get("velocity.error.one"));
    assertTrue(result.addedKeys().isEmpty());
  }

  @Test
  void addsMissingKeysAndKeepsUserValues() throws Exception {
    final Path file = dir.resolve("messages.yml");
    final String old = """
        enabled: true
        velocity:
          error:
            one: "Mine \\"quoted\\""
            custom: "not in template"
        """;
    Files.writeString(file, old);

    final MessagesFile.Result result = MessagesFile.load(file, TEMPLATE);

    assertEquals(List.of("prefix", "velocity.error.two", "paperproxy.three"), result.addedKeys());
    assertEquals(List.of("velocity.error.custom"), result.droppedKeys());
    assertEquals(null, result.values().get("velocity.error.custom"));
    assertNotNull(result.backup());
    assertEquals(old, Files.readString(result.backup()));

    final String written = Files.readString(file);
    assertTrue(written.contains("# kept comment"), written);
    assertTrue(written.contains("enabled: true"), written);
    final Map<String, String> reread = MessagesFile.parse(written, "test");
    assertEquals("Mine \"quoted\"", reread.get("velocity.error.one"));
    assertEquals("Two {server}", reread.get("velocity.error.two"));
    assertEquals("true", reread.get("enabled"));
    assertEquals(reread, result.values());
  }

  @Test
  void pluginSectionsSurviveUpdates() throws Exception {
    final Path file = dir.resolve("messages.yml");
    Files.writeString(file, """
        velocity:
          error:
            one: "Mine"
            obsolete: "old key"
        myplugin:
          welcome: "Hi {player}"
          nested:
            deep: "x"
        """);
    final MessagesFile.Result result = MessagesFile.load(file, TEMPLATE);
    assertEquals(List.of("velocity.error.obsolete"), result.droppedKeys());
    final Map<String, String> reread = MessagesFile.parse(Files.readString(file), "test");
    assertEquals("Hi {player}", reread.get("myplugin.welcome"));
    assertEquals("x", reread.get("myplugin.nested.deep"));
    assertEquals("Mine", reread.get("velocity.error.one"));
    assertEquals(null, reread.get("velocity.error.obsolete"));
    assertEquals("Hi {player}", result.values().get("myplugin.welcome"));
  }

  @Test
  void renderEscapesSpecialCharacters() throws Exception {
    final String rendered = MessagesFile.render(TEMPLATE,
        Map.of("velocity.error.one", "a\\b \"c\"\nnext"));
    assertEquals("a\\b \"c\"\nnext",
        MessagesFile.parse(rendered, "test").get("velocity.error.one"));
  }

  @Test
  void invalidYamlNamesTheLine() throws Exception {
    final Path file = dir.resolve("messages.yml");
    Files.writeString(file, "velocity:\n  error:\n    one: \"unclosed\n");
    final MessagesFile.InvalidFileException e = assertThrows(
        MessagesFile.InvalidFileException.class, () -> MessagesFile.load(file, TEMPLATE));
    assertTrue(e.getMessage().contains("line"), e.getMessage());
  }

  @Test
  void bundledFilesHaveTheSameKeys() throws Exception {
    final Map<String, String> en = MessagesFile.parse(resource("messages.yml"), "en");
    final Map<String, String> de = MessagesFile.parse(resource("messages_de.yml"), "de");
    final java.util.Set<String> enKeys = new java.util.TreeSet<>(en.keySet());
    enKeys.remove("per-client-language");
    assertEquals(enKeys, new java.util.TreeSet<>(de.keySet()));
    for (final String key : enKeys) {
      assertEquals(MessageFormatter.placeholders(en.get(key)),
          MessageFormatter.placeholders(de.get(key)), key);
    }
  }

  @Test
  void bundledTemplateSurvivesRender() throws Exception {
    final String template = resource("messages.yml");
    final Map<String, String> values = MessagesFile.parse(template, "en");
    assertEquals(template, MessagesFile.render(template, values));
  }

  private static String resource(final String name) throws Exception {
    try (InputStream in = MessagesFileTest.class.getResourceAsStream("/paperproxy/" + name)) {
      assertNotNull(in, name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
