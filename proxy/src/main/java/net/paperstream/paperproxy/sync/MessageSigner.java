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

package net.paperstream.paperproxy.sync;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Signs messages between proxies with HMAC-SHA256 over a shared secret, so a client that can
 * publish to Redis cannot send alerts, kicks or bans. Each message carries a time and a random
 * id; old or repeated messages are refused.
 */
final class MessageSigner {

  /** How far apart the clocks of two proxies may be. */
  static final long MAX_AGE_MILLIS = TimeUnit.SECONDS.toMillis(30);

  private final byte[] key;
  private final SecureRandom random = new SecureRandom();
  private final Cache<String, Boolean> seen = Caffeine.newBuilder()
      .expireAfterWrite(2 * MAX_AGE_MILLIS, TimeUnit.MILLISECONDS)
      .maximumSize(100_000)
      .build();

  MessageSigner(final String secret) {
    this.key = secret.getBytes(StandardCharsets.UTF_8);
  }

  boolean enabled() {
    return key.length > 0;
  }

  /**
   * Adds time, id and signature to a message.
   *
   * @param message the message with from, type and data
   * @param now the current time in millis
   */
  void sign(final JsonObject message, final long now) {
    if (!enabled()) {
      return;
    }
    final byte[] id = new byte[12];
    random.nextBytes(id);
    message.addProperty("time", now);
    message.addProperty("id", Base64.getEncoder().encodeToString(id));
    message.addProperty("sig", mac(payload(message)));
  }

  /**
   * Checks a received message.
   *
   * @param message the message
   * @param now the current time in millis
   * @return null if it may be used, otherwise why not
   */
  @Nullable String check(final JsonObject message, final long now) {
    if (!enabled()) {
      return null;
    }
    if (!message.has("sig") || !message.has("time") || !message.has("id")) {
      return "unsigned";
    }
    final byte[] expected = mac(payload(message)).getBytes(StandardCharsets.US_ASCII);
    final byte[] given = message.get("sig").getAsString().getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(expected, given)) {
      return "bad signature";
    }
    if (Math.abs(now - message.get("time").getAsLong()) > MAX_AGE_MILLIS) {
      return "too old";
    }
    if (seen.asMap().putIfAbsent(message.get("id").getAsString(), Boolean.TRUE) != null) {
      return "replayed";
    }
    return null;
  }

  private static String payload(final JsonObject message) {
    // Length-prefixed fields: no value can shift the boundary of another.
    final StringBuilder out = new StringBuilder();
    for (final String field : new String[] {"from", "type", "data", "time", "id"}) {
      final String value = message.has(field) ? message.get(field).getAsString() : "";
      out.append(value.length()).append(':').append(value).append(';');
    }
    return out.toString();
  }

  private String mac(final String payload) {
    try {
      final Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }
}
