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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Reads a messages file and keeps it in sync with the bundled template.
 *
 * <p>When the template contains keys the user's file lacks (typically after an update), the
 * file is rewritten from the template with the user's values filled in, and the old file is kept
 * as a backup. The template is a controlled format: two space indentation, one key per line.
 */
final class MessagesFile {

  private static final Pattern KEY_LINE = Pattern.compile("^( *)([A-Za-z0-9_-]+):(.*)$");
  private static final DateTimeFormatter BACKUP_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private MessagesFile() {
    throw new AssertionError();
  }

  /**
   * The outcome of loading one file.
   *
   * @param values flattened key/value pairs, keys joined with dots
   * @param addedKeys keys that were added from the template
   * @param droppedKeys user keys the template does not know; only reported when rewritten
   * @param backup the backup written before rewriting, or null
   */
  record Result(Map<String, String> values, List<String> addedKeys, List<String> droppedKeys,
                @Nullable Path backup) {
  }

  /**
   * Thrown when a file is not valid YAML. The message names file, line and column.
   */
  static final class InvalidFileException extends Exception {

    private static final long serialVersionUID = 1L;

    InvalidFileException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Loads {@code file}, creating it from the template if it does not exist and adding missing
   * keys if it does.
   *
   * @param file the file on disk
   * @param template the bundled template text, or null for files without template
   * @return the loaded values
   * @throws IOException if the file cannot be read or written
   * @throws InvalidFileException if the file is not valid YAML
   */
  static Result load(final Path file, final @Nullable String template)
      throws IOException, InvalidFileException {
    if (!Files.exists(file)) {
      if (template == null) {
        return new Result(Map.of(), List.of(), List.of(), null);
      }
      Files.writeString(file, template, StandardCharsets.UTF_8);
      return new Result(parse(template, "bundled " + file.getFileName()), List.of(), List.of(),
          null);
    }

    final Map<String, String> user =
        parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    if (template == null) {
      return new Result(user, List.of(), List.of(), null);
    }

    final Map<String, String> defaults = parse(template, "bundled " + file.getFileName());
    final List<String> added = new ArrayList<>();
    for (final String key : defaults.keySet()) {
      if (!user.containsKey(key)) {
        added.add(key);
      }
    }
    if (added.isEmpty()) {
      return new Result(user, List.of(), List.of(), null);
    }

    // Keys in sections the template does not have (e.g. "myplugin:") belong to plugins and are
    // kept. Unknown keys inside PaperProxy's own sections are outdated and dropped.
    final java.util.Set<String> templateSections = new java.util.HashSet<>();
    for (final String key : defaults.keySet()) {
      templateSections.add(key.split("\\.", 2)[0]);
    }
    final List<String> dropped = new ArrayList<>();
    final Map<String, String> kept = new LinkedHashMap<>();
    for (final Map.Entry<String, String> entry : user.entrySet()) {
      final String key = entry.getKey();
      if (defaults.containsKey(key)) {
        continue;
      }
      if (templateSections.contains(key.split("\\.", 2)[0])) {
        dropped.add(key);
      } else {
        kept.put(key, entry.getValue());
      }
    }

    final Path backup = file.resolveSibling(file.getFileName() + ".backup-"
        + LocalDateTime.now().format(BACKUP_STAMP));
    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(file, render(template, user) + renderExtra(kept), StandardCharsets.UTF_8);

    final Map<String, String> merged = new LinkedHashMap<>(defaults);
    merged.putAll(user);
    merged.keySet().removeAll(dropped);
    return new Result(merged, added, dropped, backup);
  }

  /**
   * Parses YAML into a flat map. Nested sections are joined with dots; only scalar values are
   * kept.
   */
  static Map<String, String> parse(final String text, final String source)
      throws InvalidFileException {
    final Object root;
    try {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    } catch (final MarkedYAMLException e) {
      final Mark mark = e.getProblemMark();
      final String where = mark == null ? "" : " line " + (mark.getLine() + 1) + ", column "
          + (mark.getColumn() + 1);
      throw new InvalidFileException(source + where + ": " + e.getProblem(), e);
    } catch (final YAMLException e) {
      throw new InvalidFileException(source + ": " + e.getMessage(), e);
    }
    final Map<String, String> out = new LinkedHashMap<>();
    if (root instanceof Map<?, ?> map) {
      flatten("", map, out);
    } else if (root != null) {
      throw new InvalidFileException(source + ": expected key/value pairs at the top level",
          new IllegalStateException());
    }
    return out;
  }

  private static void flatten(final String path, final Map<?, ?> map,
                              final Map<String, String> out) {
    for (final Map.Entry<?, ?> entry : map.entrySet()) {
      final String key = path.isEmpty() ? String.valueOf(entry.getKey())
          : path + "." + entry.getKey();
      final Object value = entry.getValue();
      if (value instanceof Map<?, ?> child) {
        flatten(key, child, out);
      } else if (value != null && !(value instanceof Iterable<?>)) {
        out.put(key, String.valueOf(value));
      }
    }
  }

  /**
   * Renders the template with the given values. Comments and layout of the template stay as
   * they are; values the user changed replace the defaults.
   */
  static String render(final String template, final Map<String, String> values) {
    final StringBuilder out = new StringBuilder(template.length() + 256);
    final Deque<String> path = new ArrayDeque<>();
    final Deque<Integer> indents = new ArrayDeque<>();
    for (final String line : template.split("\n", -1)) {
      final Matcher matcher = KEY_LINE.matcher(line);
      if (line.stripLeading().startsWith("#") || !matcher.matches()) {
        out.append(line).append('\n');
        continue;
      }
      final int indent = matcher.group(1).length();
      while (!indents.isEmpty() && indents.peek() >= indent) {
        indents.pop();
        path.pop();
      }
      final String name = matcher.group(2);
      final String rest = matcher.group(3).trim();
      if (rest.isEmpty()) {
        indents.push(indent);
        path.push(name);
        out.append(line).append('\n');
        continue;
      }
      final StringBuilder key = new StringBuilder();
      path.descendingIterator().forEachRemaining(part -> key.append(part).append('.'));
      key.append(name);
      final String value = values.get(key.toString());
      if (value == null) {
        out.append(line).append('\n');
      } else {
        final String written = rest.startsWith("\"") || rest.startsWith("'")
            ? quote(value) : value;
        out.append(matcher.group(1)).append(name).append(": ").append(written).append('\n');
      }
    }
    // split(-1) produced one empty trailing element for the final newline
    out.setLength(out.length() - 1);
    return out.toString();
  }

  /**
   * Writes keys of own sections (plugins) back as nested YAML.
   */
  static String renderExtra(final Map<String, String> extra) {
    if (extra.isEmpty()) {
      return "";
    }
    final Map<String, Object> tree = new LinkedHashMap<>();
    for (final Map.Entry<String, String> entry : extra.entrySet()) {
      final String[] parts = entry.getKey().split("\\.");
      Map<String, Object> node = tree;
      for (int i = 0; i < parts.length - 1; i++) {
        final Object child = node.computeIfAbsent(parts[i], k -> new LinkedHashMap<String, Object>());
        if (!(child instanceof Map<?, ?>)) {
          break;
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> next = (Map<String, Object>) child;
        node = next;
      }
      node.put(parts[parts.length - 1], entry.getValue());
    }
    final org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
    options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
    options.setDefaultScalarStyle(org.yaml.snakeyaml.DumperOptions.ScalarStyle.DOUBLE_QUOTED);
    options.setIndent(2);
    options.setWidth(Integer.MAX_VALUE);
    return "\n# Your own sections (for example from plugins)\n"
        + new Yaml(options).dump(tree).replaceAll("\"([A-Za-z0-9_-]+)\":", "$1:");
  }

  static String quote(final String value) {
    return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
  }
}
