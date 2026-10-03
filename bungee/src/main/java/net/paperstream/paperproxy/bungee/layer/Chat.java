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

package net.paperstream.paperproxy.bungee.layer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Converts between BungeeCord chat components and Adventure components using JSON.
 */
public final class Chat {

  /**
   * Writes the 1.21 JSON format (camelCase events), which every BungeeCord API version reads.
   */
  private static final GsonComponentSerializer GSON = GsonComponentSerializer.builder()
      .options(JSONOptions.byDataVersion().at(3953))
      .build();
  private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
      .character(LegacyComponentSerializer.SECTION_CHAR)
      .hexColors()
      .useUnusualXRepeatedCharacterHexFormat()
      .build();

  private Chat() {
    throw new AssertionError();
  }

  /**
   * Converts BungeeCord components into one Adventure component.
   *
   * @param components the components, may be empty
   * @return the Adventure component
   */
  public static Component toAdventure(final BaseComponent @Nullable ... components) {
    if (components == null || components.length == 0) {
      return Component.empty();
    }
    if (components.length == 1) {
      return components[0] == null ? Component.empty()
          : GSON.deserialize(ComponentSerializer.toString(components[0]));
    }
    return GSON.deserialize(ComponentSerializer.toString(components));
  }

  /**
   * Converts a legacy text with section sign color codes.
   *
   * @param legacy the text
   * @return the Adventure component
   */
  public static Component fromLegacy(final @Nullable String legacy) {
    return legacy == null ? Component.empty() : LEGACY.deserialize(legacy);
  }

  /**
   * Converts an Adventure component into one BungeeCord component.
   *
   * @param component the component
   * @return the BungeeCord component
   */
  public static BaseComponent toBungee(final @Nullable Component component) {
    if (component == null) {
      return new TextComponent("");
    }
    return ComponentSerializer.deserialize(GSON.serialize(component));
  }

  /**
   * Converts an Adventure component into a legacy text.
   *
   * @param component the component
   * @return the text with section sign color codes
   */
  public static String toLegacy(final @Nullable Component component) {
    return component == null ? "" : LEGACY.serialize(component);
  }
}
