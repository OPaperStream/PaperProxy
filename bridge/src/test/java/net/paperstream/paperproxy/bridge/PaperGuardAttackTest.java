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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.paperstream.paperproxy.forwarding.PaperGuardCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Signs with the proxy's code, verifies with the bridge's code. Every attack must be rejected.
 */
class PaperGuardAttackTest {

  private static final byte[] MASTER = "0123456789abcdef0123456789abcdef".getBytes(
      StandardCharsets.US_ASCII);
  private static final long NOW = 1_800_000_000L;
  private static final String HOST = "play.example.net";
  private static final String IP = "203.0.113.7";
  private static final String UUID = "f3d28cb072253cb1baeb2dadd2be89ae";

  private byte[] lobbyKey;
  private PaperGuardVerifier lobby;
  private int nonceCounter;

  @BeforeEach
  void setUp() {
    lobbyKey = PaperGuardCodec.serverKey(MASTER, "lobby");
    lobby = new PaperGuardVerifier(lobbyKey, "lobby", 10);
  }

  private List<PaperGuardCodec.Property> profile() {
    final List<PaperGuardCodec.Property> properties = new ArrayList<>();
    properties.add(new PaperGuardCodec.Property("textures", "skin-data", "mojang-signature"));
    return properties;
  }

  private String sign(final byte[] key, final String server, final long time, final String host,
                      final String ip, final String uuid) {
    final byte[] nonce = new byte[16];
    nonce[0] = (byte) ++nonceCounter;
    return PaperGuardCodec.sign(key, server, time, nonce, host, ip, uuid, profile());
  }

  private static List<PaperGuardVerifier.Property> bridgeProperties(final String paperguard) {
    final List<PaperGuardVerifier.Property> properties = new ArrayList<>();
    properties.add(new PaperGuardVerifier.Property("textures", "skin-data", "mojang-signature"));
    if (paperguard != null) {
      properties.add(new PaperGuardVerifier.Property(PaperGuardCodec.PROPERTY, paperguard, ""));
    }
    return properties;
  }

  @Test
  void validLoginIsAccepted() {
    final String value = sign(lobbyKey, "lobby", NOW, HOST, IP, UUID);
    assertEquals(PaperGuardVerifier.Result.OK,
        lobby.verify(HOST, IP, UUID, bridgeProperties(value), NOW));
  }

  @Test
  void replayIsRejected() {
    final String value = sign(lobbyKey, "lobby", NOW, HOST, IP, UUID);
    assertEquals(PaperGuardVerifier.Result.OK,
        lobby.verify(HOST, IP, UUID, bridgeProperties(value), NOW));
    assertEquals(PaperGuardVerifier.Result.REPLAYED,
        lobby.verify(HOST, IP, UUID, bridgeProperties(value), NOW + 1));
  }

  @Test
  void tamperedIpIsRejected() {
    final String value = sign(lobbyKey, "lobby", NOW, HOST, IP, UUID);
    assertEquals(PaperGuardVerifier.Result.BAD_SIGNATURE,
        lobby.verify(HOST, "198.51.100.1", UUID, bridgeProperties(value), NOW));
  }

  @Test
  void tamperedUuidIsRejected() {
    final String value = sign(lobbyKey, "lobby", NOW, HOST, IP, UUID);
    assertEquals(PaperGuardVerifier.Result.BAD_SIGNATURE,
        lobby.verify(HOST, IP, "00000000000000000000000000000001", bridgeProperties(value), NOW));
  }

  @Test
  void tamperedHostIsRejected() {
    final String value = sign(lobbyKey, "lobby", NOW, HOST, IP, UUID);
    assertEquals(PaperGuardVerifier.Result.BAD_SIGNATURE,
        lobby.verify("evil.example.net", IP, UUID, bridgeProperties(value), NOW));
  }

  @Test
  void tamperedPropertiesAreRejected() {
    final String value = sign(lobbyKey, "lobby", NOW, HOST, IP, UUID);
    final List<PaperGuardVerifier.Property> properties = new ArrayList<>();
    properties.add(new PaperGuardVerifier.Property("textures", "other-skin", "mojang-signature"));
    properties.add(new PaperGuardVerifier.Property(PaperGuardCodec.PROPERTY, value, ""));
    assertEquals(PaperGuardVerifier.Result.BAD_SIGNATURE,
        lobby.verify(HOST, IP, UUID, properties, NOW));
  }

  @Test
  void addedPropertyIsRejected() {
    final String value = sign(lobbyKey, "lobby", NOW, HOST, IP, UUID);
    final List<PaperGuardVerifier.Property> properties = bridgeProperties(value);
    properties.add(new PaperGuardVerifier.Property("injected", "x", ""));
    assertEquals(PaperGuardVerifier.Result.BAD_SIGNATURE,
        lobby.verify(HOST, IP, UUID, properties, NOW));
  }

  @Test
  void loginForAnotherServerIsRejected() {
    final byte[] survivalKey = PaperGuardCodec.serverKey(MASTER, "survival");
    final String forSurvival = sign(survivalKey, "survival", NOW, HOST, IP, UUID);
    assertEquals(PaperGuardVerifier.Result.BAD_SIGNATURE,
        lobby.verify(HOST, IP, UUID, bridgeProperties(forSurvival), NOW));
    // Even a leaked lobby key cannot sign for another server name and pass on lobby.
    final String renamed = sign(lobbyKey, "survival", NOW, HOST, IP, UUID);
    assertEquals(PaperGuardVerifier.Result.BAD_SIGNATURE,
        lobby.verify(HOST, IP, UUID, bridgeProperties(renamed), NOW));
  }

  @Test
  void wrongSecretIsRejected() {
    final byte[] other = PaperGuardCodec.serverKey(
        "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.US_ASCII), "lobby");
    final String value = sign(other, "lobby", NOW, HOST, IP, UUID);
    assertEquals(PaperGuardVerifier.Result.BAD_SIGNATURE,
        lobby.verify(HOST, IP, UUID, bridgeProperties(value), NOW));
  }

  @Test
  void oldLoginIsRejected() {
    final String value = sign(lobbyKey, "lobby", NOW - 11, HOST, IP, UUID);
    assertEquals(PaperGuardVerifier.Result.EXPIRED,
        lobby.verify(HOST, IP, UUID, bridgeProperties(value), NOW));
  }

  @Test
  void loginFromTheFutureIsRejected() {
    final String value = sign(lobbyKey, "lobby", NOW + 11, HOST, IP, UUID);
    assertEquals(PaperGuardVerifier.Result.EXPIRED,
        lobby.verify(HOST, IP, UUID, bridgeProperties(value), NOW));
  }

  @Test
  void missingSignatureIsRejected() {
    assertEquals(PaperGuardVerifier.Result.MISSING,
        lobby.verify(HOST, IP, UUID, bridgeProperties(null), NOW));
  }

  @Test
  void malformedSignaturesAreRejected() {
    for (final String bad : new String[] {"", "v1", "v1:1:2", "v2:1:abc:def", "v1:x:abc:def",
        "v1:1::def", "v1:1:abc:!!!", "v1:1:abc:def:extra"}) {
      final PaperGuardVerifier.Result result =
          lobby.verify(HOST, IP, UUID, bridgeProperties(bad), NOW);
      assertEquals(true, result == PaperGuardVerifier.Result.MALFORMED
          || result == PaperGuardVerifier.Result.BAD_SIGNATURE, bad + " -> " + result);
    }
  }

  @Test
  void duplicatePaperGuardPropertyIsRejected() {
    final String value = sign(lobbyKey, "lobby", NOW, HOST, IP, UUID);
    final List<PaperGuardVerifier.Property> properties = bridgeProperties(value);
    properties.add(new PaperGuardVerifier.Property(PaperGuardCodec.PROPERTY, value, ""));
    assertEquals(PaperGuardVerifier.Result.MALFORMED,
        lobby.verify(HOST, IP, UUID, properties, NOW));
  }

  @Test
  void sameWireFormatOnBothSides() {
    final List<PaperGuardVerifier.Property> bridge = bridgeProperties(null);
    assertEquals(new String(PaperGuardCodec.payload("Lobby", 5, "n", HOST, IP, UUID, profile()),
            StandardCharsets.UTF_8),
        new String(PaperGuardVerifier.payload("lobby", 5, "n", HOST, IP, UUID, bridge),
            StandardCharsets.UTF_8));
  }
}
