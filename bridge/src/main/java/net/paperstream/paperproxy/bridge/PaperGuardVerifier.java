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

package net.paperstream.paperproxy.bridge;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checks PaperGuard logins. The signature format is a copy of PaperProxy's PaperGuardCodec and
 * is tested against the same vectors. Java 8 compatible on purpose.
 */
final class PaperGuardVerifier {

  static final String PROPERTY = "paperguard";
  private static final String VERSION = "v1";

  /** Why a login was rejected. */
  enum Result {
    OK,
    MISSING,
    MALFORMED,
    BAD_SIGNATURE,
    EXPIRED,
    REPLAYED
  }

  /** One profile property. */
  static final class Property {
    final String name;
    final String value;
    final String signature;

    Property(final String name, final String value, final String signature) {
      this.name = name;
      this.value = value;
      this.signature = signature == null ? "" : signature;
    }
  }

  private final byte[] key;
  private final String serverName;
  private final long maxAgeSeconds;
  /** Nonce -> expiry (unix seconds). */
  private final Map<String, Long> seen = new ConcurrentHashMap<>();

  PaperGuardVerifier(final byte[] key, final String serverName, final long maxAgeSeconds) {
    this.key = key.clone();
    this.serverName = serverName;
    this.maxAgeSeconds = maxAgeSeconds;
  }

  /**
   * Verifies one login.
   *
   * @param host host field of the handshake
   * @param ip player IP field
   * @param undashedUuid UUID field
   * @param properties all properties including the PaperGuard one
   * @param nowSeconds the current unix time
   * @return the result
   */
  Result verify(final String host, final String ip, final String undashedUuid,
                final List<Property> properties, final long nowSeconds) {
    String value = null;
    for (final Property property : properties) {
      if (PROPERTY.equals(property.name)) {
        if (value != null) {
          return Result.MALFORMED;
        }
        value = property.value;
      }
    }
    if (value == null) {
      return Result.MISSING;
    }
    final String[] parts = value.split(":", -1);
    if (parts.length != 4 || !VERSION.equals(parts[0]) || parts[2].isEmpty()) {
      return Result.MALFORMED;
    }
    final long timestamp;
    final byte[] given;
    try {
      timestamp = Long.parseLong(parts[1]);
      given = Base64.getUrlDecoder().decode(parts[3]);
    } catch (final IllegalArgumentException e) {
      return Result.MALFORMED;
    }
    final byte[] expected = hmac(key, payload(serverName, timestamp, parts[2], host, ip,
        undashedUuid, properties));
    if (!MessageDigest.isEqual(expected, given)) {
      return Result.BAD_SIGNATURE;
    }
    if (Math.abs(nowSeconds - timestamp) > maxAgeSeconds) {
      return Result.EXPIRED;
    }
    cleanup(nowSeconds);
    // putIfAbsent makes the check and the insert one atomic step.
    if (seen.putIfAbsent(parts[2], nowSeconds + 2 * maxAgeSeconds) != null) {
      return Result.REPLAYED;
    }
    return Result.OK;
  }

  private void cleanup(final long nowSeconds) {
    for (final Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator(); it.hasNext(); ) {
      if (it.next().getValue() < nowSeconds) {
        it.remove();
      }
    }
  }

  static byte[] payload(final String serverName, final long timestamp, final String nonce,
                        final String host, final String ip, final String undashedUuid,
                        final List<Property> properties) {
    final StringBuilder out = new StringBuilder(256)
        .append(VERSION).append('\n')
        .append(serverName.toLowerCase(Locale.ROOT)).append('\n')
        .append(timestamp).append('\n')
        .append(nonce).append('\n')
        .append(host).append('\n')
        .append(ip).append('\n')
        .append(undashedUuid.toLowerCase(Locale.ROOT)).append('\n');
    for (final Property property : properties) {
      if (PROPERTY.equals(property.name)) {
        continue;
      }
      out.append(property.name).append('\0')
          .append(property.value).append('\0')
          .append(property.signature).append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  static byte[] hmac(final byte[] key, final byte[] data) {
    try {
      final Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }
}
