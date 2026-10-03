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

package net.paperstream.paperproxy.config;

import com.velocitypowered.api.network.ProtocolVersion;
import java.util.Locale;

/**
 * A range of client versions such as {@code 1.8-1.8.9}, {@code 1.20.5-latest}, {@code 1.13+} or
 * a single version.
 *
 * @param min the oldest allowed version
 * @param max the newest allowed version
 * @param text the range as written in the config
 */
public record VersionRange(ProtocolVersion min, ProtocolVersion max, String text) {

  /**
   * Parses a range.
   *
   * @param text the text
   * @return the range
   * @throws IllegalArgumentException with a readable message if the text is invalid
   */
  public static VersionRange parse(final String text) {
    final String value = text.trim().toLowerCase(Locale.ROOT);
    final ProtocolVersion min;
    final ProtocolVersion max;
    if (value.endsWith("+")) {
      min = version(value.substring(0, value.length() - 1));
      max = ProtocolVersion.MAXIMUM_VERSION;
    } else if (value.contains("-")) {
      final int dash = value.indexOf('-');
      min = version(value.substring(0, dash));
      final String upper = value.substring(dash + 1);
      max = upper.equals("latest") ? ProtocolVersion.MAXIMUM_VERSION : version(upper);
    } else {
      min = version(value);
      max = min;
    }
    if (min.compareTo(max) > 0) {
      throw new IllegalArgumentException("'" + text + "': the first version must be the older one");
    }
    return new VersionRange(min, max, text.trim());
  }

  /**
   * Tells whether a client version is inside the range.
   *
   * @param version the client version
   * @return true if allowed
   */
  public boolean contains(final ProtocolVersion version) {
    return version.compareTo(min) >= 0 && version.compareTo(max) <= 0;
  }

  private static ProtocolVersion version(final String name) {
    final String trimmed = name.trim();
    if (trimmed.equals("latest")) {
      return ProtocolVersion.MAXIMUM_VERSION;
    }
    for (final ProtocolVersion version : ProtocolVersion.values()) {
      if (version.isUnknown() || version.isLegacy()) {
        continue;
      }
      if (version.getVersionsSupportedBy().contains(trimmed)) {
        return version;
      }
    }
    throw new IllegalArgumentException("'" + trimmed + "' is not a Minecraft version PaperProxy "
        + "knows (oldest " + ProtocolVersion.MINIMUM_VERSION.getVersionIntroducedIn() + ", newest "
        + ProtocolVersion.MAXIMUM_VERSION.getMostRecentSupportedVersion() + ")");
  }
}
