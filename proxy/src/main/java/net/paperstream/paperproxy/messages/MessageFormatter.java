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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the user friendly text from {@code messages.yml} into a MiniMessage string that the
 * translation system understands.
 *
 * <p>Three things happen, in this order: {@code {prefix}} is inserted, named placeholders such as
 * {@code {server}} become MiniMessage argument tags, and legacy {@code &} color codes become
 * MiniMessage tags.
 */
public final class MessageFormatter {

  /**
   * Velocity passes most arguments by position only. These names make them usable as
   * {@code {name}} in messages.yml; the list index is the argument position.
   */
  static final Map<String, List<String>> POSITIONAL_NAMES = Map.ofEntries(
      Map.entry("velocity.error.cant-connect", List.of("server", "reason")),
      Map.entry("velocity.error.connecting-server-error", List.of("server")),
      Map.entry("velocity.error.connected-server-error", List.of("server")),
      Map.entry("velocity.error.moved-to-new-server", List.of("server", "reason")),
      Map.entry("velocity.command.server-does-not-exist", List.of("server")),
      Map.entry("velocity.command.player-not-found", List.of("player")),
      Map.entry("velocity.command.server-current-server", List.of("server")),
      Map.entry("velocity.command.server-tooltip-player-online", List.of("count")),
      Map.entry("velocity.command.server-tooltip-players-online", List.of("count")),
      Map.entry("velocity.command.glist-player-singular", List.of("count")),
      Map.entry("velocity.command.glist-player-plural", List.of("count")),
      Map.entry("velocity.command.version-copyright", List.of("vendor", "name", "year")),
      Map.entry("velocity.command.plugins-list", List.of("plugins")),
      Map.entry("velocity.command.plugin-tooltip-website", List.of("url")),
      Map.entry("velocity.command.plugin-tooltip-author", List.of("author")),
      Map.entry("velocity.command.plugin-tooltip-authors", List.of("authors"))
  );

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");
  private static final String PREFIX_PLACEHOLDER = "{prefix}";

  private static final Map<Character, String> LEGACY_CODES = Map.ofEntries(
      Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
      Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
      Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
      Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
      Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
      Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
      Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
      Map.entry('r', "reset")
  );

  private MessageFormatter() {
    throw new AssertionError();
  }

  /**
   * Converts a raw messages.yml value into a MiniMessage string.
   *
   * @param key the translation key the value belongs to
   * @param raw the value as written by the user
   * @param prefix the already converted prefix, inserted for {@code {prefix}}
   * @return the MiniMessage string
   */
  public static String toMiniMessage(final String key, final String raw, final String prefix) {
    String text = raw.replace(PREFIX_PLACEHOLDER, prefix);
    text = replacePlaceholders(key, text);
    return legacyToMiniMessage(text);
  }

  /**
   * Converts legacy {@code &} color codes, including {@code &#rrggbb}, to MiniMessage tags.
   * An {@code &} that is not followed by a valid code is kept as it is.
   *
   * <p>A legacy color code also clears bold, italic and the like. To keep that behaviour every
   * color becomes {@code <reset><color>}.
   *
   * @param text the text to convert
   * @return the converted text
   */
  public static String legacyToMiniMessage(final String text) {
    if (text.indexOf('&') < 0) {
      return text;
    }
    final StringBuilder out = new StringBuilder(text.length() + 16);
    int i = 0;
    while (i < text.length()) {
      final char c = text.charAt(i);
      if (c == '&' && i + 1 < text.length()) {
        final char code = Character.toLowerCase(text.charAt(i + 1));
        if (code == '#' && i + 8 <= text.length() && isHex(text, i + 2, i + 8)) {
          out.append("<reset><color:#").append(text, i + 2, i + 8).append('>');
          i += 8;
          continue;
        }
        final String tag = LEGACY_CODES.get(code);
        if (tag != null) {
          if (isColor(code)) {
            out.append("<reset><").append(tag).append('>');
          } else {
            out.append('<').append(tag).append('>');
          }
          i += 2;
          continue;
        }
      }
      out.append(c);
      i++;
    }
    return out.toString();
  }

  /**
   * Returns the placeholders a text uses, without {@code {prefix}}.
   *
   * @param text the text to scan
   * @return the placeholder names in order of appearance
   */
  public static Set<String> placeholders(final String text) {
    final Set<String> names = new LinkedHashSet<>();
    final Matcher matcher = PLACEHOLDER.matcher(text);
    while (matcher.find()) {
      if (!"prefix".equals(matcher.group(1))) {
        names.add(matcher.group(1));
      }
    }
    return names;
  }

  private static String replacePlaceholders(final String key, final String text) {
    if (text.indexOf('{') < 0) {
      return text;
    }
    final List<String> positional = POSITIONAL_NAMES.getOrDefault(key, List.of());
    final Matcher matcher = PLACEHOLDER.matcher(text);
    final StringBuilder out = new StringBuilder(text.length());
    while (matcher.find()) {
      final String name = matcher.group(1);
      final int index = positional.indexOf(name);
      final String tag = index >= 0 ? "<arg:" + index + ">" : "<" + name + ">";
      matcher.appendReplacement(out, Matcher.quoteReplacement(tag));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static boolean isColor(final char code) {
    return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f');
  }

  private static boolean isHex(final String text, final int from, final int to) {
    for (int i = from; i < to; i++) {
      if (Character.digit(text.charAt(i), 16) < 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Normalises a client locale to the language tags used in file names, most specific first.
   *
   * @param locale the client locale
   * @return for example {@code ["de_de", "de"]}
   */
  static List<String> languageCandidates(final Locale locale) {
    final String language = locale.getLanguage().toLowerCase(Locale.ROOT);
    final String country = locale.getCountry().toLowerCase(Locale.ROOT);
    if (language.isEmpty()) {
      return List.of();
    }
    return country.isEmpty() ? List.of(language) : List.of(language + "_" + country, language);
  }
}
