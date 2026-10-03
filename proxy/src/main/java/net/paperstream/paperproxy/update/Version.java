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

package net.paperstream.paperproxy.update;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A PaperProxy version such as {@code 1.2.0}, {@code 1.2.0-BETA} or {@code 1.2.0-SNAPSHOT}.
 *
 * <p>Order within the same number: SNAPSHOT &lt; ALPHA &lt; BETA &lt; RELEASE (no suffix counts
 * as RELEASE). So {@code 1.2.0-RELEASE} is offered to someone running {@code 1.2.0-ALPHA}.
 *
 * @param major major
 * @param minor minor
 * @param patch patch
 * @param stage 0 snapshot, 1 alpha, 2 beta, 3 release
 * @param text the original text
 */
public record Version(int major, int minor, int patch, int stage, String text)
    implements Comparable<Version> {

  private static final Pattern PATTERN = Pattern.compile(
      "^v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-([A-Za-z]+)[.\\d]*)?.*$");

  /**
   * Parses a version or tag.
   *
   * @param text the text, e.g. {@code v1.2.0-BETA}
   * @return the version
   * @throws IllegalArgumentException if it is not a version
   */
  public static Version parse(final String text) {
    final Matcher matcher = PATTERN.matcher(text.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Not a version: " + text);
    }
    final String suffix = matcher.group(4) == null ? "release"
        : matcher.group(4).toLowerCase(Locale.ROOT);
    final int stage = switch (suffix) {
      case "snapshot", "dev" -> 0;
      case "alpha", "a" -> 1;
      case "beta", "b", "rc", "pre" -> 2;
      default -> 3;
    };
    return new Version(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
        matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)), stage, text.trim());
  }

  /**
   * Tells whether this version may be installed on the given channel.
   *
   * @param channel release, beta or alpha
   * @return true if allowed
   */
  public boolean allowedOn(final String channel) {
    return switch (channel) {
      case "alpha" -> stage >= 1;
      case "beta" -> stage >= 2;
      default -> stage >= 3;
    };
  }

  @Override
  public int compareTo(final Version other) {
    int result = Integer.compare(major, other.major);
    if (result == 0) {
      result = Integer.compare(minor, other.minor);
    }
    if (result == 0) {
      result = Integer.compare(patch, other.patch);
    }
    if (result == 0) {
      result = Integer.compare(stage, other.stage);
    }
    return result;
  }
}
