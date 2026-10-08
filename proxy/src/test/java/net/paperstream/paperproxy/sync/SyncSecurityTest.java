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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SyncSecurityTest {

  private static JsonObject message() {
    final JsonObject json = new JsonObject();
    json.addProperty("from", "proxy-1");
    json.addProperty("type", "kick");
    json.addProperty("data", "c0ffee00-0000-0000-0000-000000000000");
    return json;
  }

  @Test
  void signedMessagesPassOnceAndForgedOnesNever() {
    final MessageSigner signer = new MessageSigner("shared-secret");
    final long now = 1_000_000L;
    final JsonObject json = message();
    signer.sign(json, now);
    assertNull(signer.check(json.deepCopy(), now));
    assertEquals("replayed", signer.check(json.deepCopy(), now));

    final JsonObject tampered = message();
    signer.sign(tampered, now);
    tampered.addProperty("data", "someone-else");
    assertEquals("bad signature", signer.check(tampered, now));

    final JsonObject old = message();
    signer.sign(old, now);
    assertEquals("too old", signer.check(old, now + MessageSigner.MAX_AGE_MILLIS + 1));

    assertEquals("unsigned", signer.check(message(), now));
    final JsonObject other = message();
    new MessageSigner("another-secret").sign(other, now);
    assertEquals("bad signature", signer.check(other, now));
  }

  @Test
  void withoutSecretMessagesAreAccepted() {
    assertNull(new MessageSigner("").check(message(), 0));
  }

  private static RedisConnection reading(final String reply) {
    return new RedisConnection(new Socket(),
        new ByteArrayInputStream(reply.getBytes(StandardCharsets.UTF_8)),
        new ByteArrayOutputStream());
  }

  @Test
  void hugeRepliesAreRefusedBeforeAllocating() {
    assertThrows(IOException.class, () -> reading("*2000000000\r\n").call("PING"));
    assertThrows(IOException.class, () -> reading("$2000000000\r\n").call("PING"));
    assertThrows(IOException.class,
        () -> reading("+" + "x".repeat(RedisConnection.MAX_LINE_BYTES + 10) + "\r\n")
            .call("PING"));
  }

  @Test
  void normalRepliesStillWork() throws Exception {
    assertEquals("PONG", reading("+PONG\r\n").call("PING"));
    assertEquals(java.util.List.of("a", "b"), reading("*2\r\n$1\r\na\r\n$1\r\nb\r\n")
        .call("SMEMBERS", "x"));
  }
}
