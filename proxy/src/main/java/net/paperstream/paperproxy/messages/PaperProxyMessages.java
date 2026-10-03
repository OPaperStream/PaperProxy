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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.translation.MiniMessageTranslator;
import net.kyori.adventure.translation.Translator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Owns {@code messages.yml} and the optional per-language files, and serves their texts to the
 * translation system.
 *
 * <p>Loading never throws: a broken file is reported with file, line and column, and the
 * previously loaded texts (or the bundled defaults on first start) stay active.
 */
public final class PaperProxyMessages {

  private static final Logger logger = LogManager.getLogger(PaperProxyMessages.class);

  private static final String BASE_FILE = "messages.yml";
  private static final String TEMPLATE_DIR = "/paperproxy/";
  /** Language files shipped with PaperProxy; written on first start. */
  private static final List<String> BUNDLED_LANGUAGES = List.of("de");

  private static final String SETTING_PER_CLIENT = "per-client-language";
  private static final String SETTING_PREFIX = "prefix";
  private static final Set<String> SETTINGS = Set.of(SETTING_PER_CLIENT, SETTING_PREFIX);

  private final Path directory;
  private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), false);

  /**
   * Converted texts, ready for MiniMessage.
   *
   * @param base texts from messages.yml
   * @param languages texts per language tag (e.g. "de", "de_de")
   * @param perClientLanguage whether language files are used
   */
  private record Snapshot(Map<String, String> base, Map<String, Map<String, String>> languages,
                          boolean perClientLanguage) {
  }

  /**
   * Creates the message store.
   *
   * @param directory the directory messages.yml lives in (the proxy root)
   */
  public PaperProxyMessages(final Path directory) {
    this.directory = directory;
  }

  /**
   * Loads or reloads all message files. On any error the previous texts stay active.
   *
   * @return true if everything loaded without errors
   */
  public boolean load() {
    try {
      final String baseTemplate = template(BASE_FILE);
      if (baseTemplate == null) {
        throw new IllegalStateException("bundled messages.yml is missing from the jar");
      }
      final Map<String, String> baseRaw = loadFile(BASE_FILE, baseTemplate);
      final String basePrefix = MessageFormatter.legacyToMiniMessage(
          baseRaw.getOrDefault(SETTING_PREFIX, ""));
      final boolean perClient = Boolean.parseBoolean(baseRaw.get(SETTING_PER_CLIENT));
      final Map<String, String> defaults = MessagesFile.parse(baseTemplate, "bundled messages.yml");
      warnUnknownPlaceholders(BASE_FILE, baseRaw, defaults);

      final Map<String, Map<String, String>> languages = new HashMap<>();
      for (final String language : BUNDLED_LANGUAGES) {
        final String name = "messages_" + language + ".yml";
        loadFile(name, template(name));
      }
      try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "messages_*.yml")) {
        for (final Path file : files) {
          final String name = file.getFileName().toString();
          final String tag = name.substring("messages_".length(), name.length() - ".yml".length())
              .toLowerCase(Locale.ROOT).replace('-', '_');
          final Map<String, String> raw = loadFile(name, template(name));
          warnUnknownPlaceholders(name, raw, defaults);
          final String prefix = raw.containsKey(SETTING_PREFIX)
              ? MessageFormatter.legacyToMiniMessage(raw.get(SETTING_PREFIX)) : basePrefix;
          languages.put(tag, convert(raw, prefix));
        }
      }

      this.snapshot = new Snapshot(convert(baseRaw, basePrefix), Map.copyOf(languages),
          perClient);
      return true;
    } catch (final MessagesFile.InvalidFileException e) {
      logger.error("Invalid messages file, keeping the previous messages: {}", e.getMessage());
    } catch (final IOException | RuntimeException e) {
      logger.error("Unable to load the messages, keeping the previous messages", e);
    }
    return false;
  }

  /**
   * Looks up the MiniMessage string for a key.
   *
   * @param key the translation key
   * @param locale the receiver's locale
   * @return the text, or null if messages.yml does not define the key
   */
  public @Nullable String lookup(final String key, final Locale locale) {
    final Snapshot current = this.snapshot;
    if (current.perClientLanguage()) {
      for (final String tag : MessageFormatter.languageCandidates(locale)) {
        final Map<String, String> texts = current.languages().get(tag);
        if (texts != null) {
          final String text = texts.get(key);
          if (text != null) {
            return text;
          }
        }
      }
    }
    return current.base().get(key);
  }

  /**
   * Creates the translator to register globally. PaperProxy texts always win; everything
   * messages.yml does not define is answered by {@code fallback}.
   *
   * <p>Adventure keeps its global sources in an unordered set, so registering both translators
   * separately would make the winner random. Hence the fallback is wrapped instead.
   *
   * @param fallback Velocity's own translation store
   * @return the combined translator
   */
  public Translator translator(final Translator fallback) {
    return new LayeredTranslator(new MessagesTranslator(this), fallback);
  }

  private Map<String, String> loadFile(final String name, final @Nullable String template)
      throws IOException, MessagesFile.InvalidFileException {
    final MessagesFile.Result result = MessagesFile.load(directory.resolve(name), template);
    if (!result.addedKeys().isEmpty()) {
      logger.info("Added {} new message(s) to {}: {}. Your texts were kept, backup: {}",
          result.addedKeys().size(), name, String.join(", ", result.addedKeys()),
          result.backup() == null ? "-" : result.backup().getFileName());
    }
    if (!result.droppedKeys().isEmpty()) {
      logger.warn("{} contained unknown keys that were not carried over: {} (still in the backup)",
          name, String.join(", ", result.droppedKeys()));
    }
    return result.values();
  }

  private static Map<String, String> convert(final Map<String, String> raw, final String prefix) {
    final Map<String, String> out = new HashMap<>();
    raw.forEach((key, value) -> {
      if (!SETTINGS.contains(key)) {
        out.put(key, MessageFormatter.toMiniMessage(key, value, prefix));
      }
    });
    return Map.copyOf(out);
  }

  private static void warnUnknownPlaceholders(final String file, final Map<String, String> raw,
                                              final Map<String, String> defaults) {
    raw.forEach((key, value) -> {
      final String original = defaults.get(key);
      if (original == null || SETTINGS.contains(key)) {
        return;
      }
      final Set<String> known = MessageFormatter.placeholders(original);
      for (final String used : MessageFormatter.placeholders(value)) {
        if (!known.contains(used)) {
          logger.warn("{}: '{}' uses the unknown placeholder {{}}. Available: {}", file, key, used,
              known.isEmpty() ? "none" : "{" + String.join("}, {", known) + "}");
        }
      }
    });
  }

  private static @Nullable String template(final String name) throws IOException {
    try (InputStream in = PaperProxyMessages.class.getResourceAsStream(TEMPLATE_DIR + name)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Serves messages.yml texts through MiniMessage, including argument tags.
   */
  private static final class MessagesTranslator extends MiniMessageTranslator {

    private static final Key NAME = Key.key("paperproxy", "messages");
    private final PaperProxyMessages messages;

    MessagesTranslator(final PaperProxyMessages messages) {
      this.messages = messages;
    }

    @Override
    protected @Nullable String getMiniMessageString(final String key, final Locale locale) {
      return messages.lookup(key, locale);
    }

    @Override
    public Key name() {
      return NAME;
    }
  }
}
