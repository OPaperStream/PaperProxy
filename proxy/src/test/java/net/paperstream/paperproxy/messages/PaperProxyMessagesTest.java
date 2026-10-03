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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslationStore;
import net.kyori.adventure.text.renderer.TranslatableComponentRenderer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.Translator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperProxyMessagesTest {

  @TempDir
  Path dir;

  private PaperProxyMessages messages;
  private Translator translator;

  @BeforeEach
  void setUp() {
    final MiniMessageTranslationStore velocity =
        MiniMessageTranslationStore.create(Key.key("test", "velocity"));
    velocity.register("velocity.only.in.velocity", Locale.US, "from velocity");
    velocity.register("velocity.error.already-connected", Locale.US, "velocity text");
    messages = new PaperProxyMessages(dir);
    translator = messages.translator(velocity);
  }

  @Test
  void writesBundledFilesOnFirstStart() {
    assertTrue(messages.load());
    assertTrue(Files.exists(dir.resolve("messages.yml")));
    assertTrue(Files.exists(dir.resolve("messages_de.yml")));
  }

  @Test
  void messagesYmlWinsOverVelocity() {
    assertTrue(messages.load());
    assertEquals("You are already connected to this server!",
        render(Component.translatable("velocity.error.already-connected"), Locale.US));
  }

  @Test
  void unknownKeysFallBackToVelocity() {
    assertTrue(messages.load());
    assertEquals("from velocity",
        render(Component.translatable("velocity.only.in.velocity"), Locale.US));
  }

  @Test
  void positionalArgumentsWork() {
    assertTrue(messages.load());
    assertEquals("Unable to connect to lobby: offline",
        render(Component.translatable("velocity.error.cant-connect",
            Component.text("lobby"), Component.text("offline")), Locale.US));
  }

  @Test
  void namedArgumentsWork() {
    assertTrue(messages.load());
    assertEquals("Based on Velocity 9.9, licensed under the GNU GPL v3.",
        render(Component.translatable("paperproxy.command.based-on",
            Argument.string("velocity_version", "9.9")), Locale.US));
  }

  @Test
  void userEditsWithLegacyColorsAndPrefix() throws Exception {
    assertTrue(messages.load());
    final Path file = dir.resolve("messages.yml");
    Files.writeString(file, Files.readString(file)
        .replace("prefix: \"<gradient:#3ad9cd:#8df3e4>PaperProxy</gradient> <dark_gray>»</dark_gray> \"",
            "prefix: \"&8[&bNet&8] \"")
        .replace("already-connected: \"You are already connected to this server!\"",
            "already-connected: \"{prefix}&cAlready here!\""));
    assertTrue(messages.load());

    final Component rendered = TranslatableComponentRenderer.usingTranslationSource(translator)
        .render(Component.translatable("velocity.error.already-connected"), Locale.US);
    assertEquals("[Net] Already here!", PlainTextComponentSerializer.plainText()
        .serialize(rendered));
    assertTrue(containsColor(rendered, NamedTextColor.RED));
  }

  @Test
  void languageFilesOnlyWhenEnabled() throws Exception {
    assertTrue(messages.load());
    final Component kick = Component.translatable("velocity.kick.shutdown");
    assertEquals("Proxy shutting down.", render(kick, Locale.GERMANY));

    final Path file = dir.resolve("messages.yml");
    Files.writeString(file, Files.readString(file)
        .replace("per-client-language: false", "per-client-language: true"));
    assertTrue(messages.load());
    assertEquals("Proxy fährt herunter.", render(kick, Locale.GERMANY));
    assertEquals("Proxy shutting down.", render(kick, Locale.US));
  }

  @Test
  void brokenFileKeepsPreviousTexts() throws Exception {
    assertTrue(messages.load());
    Files.writeString(dir.resolve("messages.yml"), "velocity: [unclosed\n");
    assertFalse(messages.load());
    assertEquals("Proxy shutting down.",
        render(Component.translatable("velocity.kick.shutdown"), Locale.US));
  }

  private String render(final Component component, final Locale locale) {
    return PlainTextComponentSerializer.plainText().serialize(
        TranslatableComponentRenderer.usingTranslationSource(translator).render(component, locale));
  }

  private static boolean containsColor(final Component component, final NamedTextColor color) {
    if (color.equals(component.color())) {
      return true;
    }
    return component.children().stream().anyMatch(child -> containsColor(child, color));
  }
}
