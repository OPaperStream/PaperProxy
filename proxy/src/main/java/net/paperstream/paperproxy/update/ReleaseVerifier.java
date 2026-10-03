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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Checks downloaded releases. A release is only installed when its SHA-512 matches the published
 * checksum and the jar is signed with the PaperProxy release key. The private key never touches
 * GitHub, so a compromised GitHub account alone cannot ship a malicious update.
 */
public final class ReleaseVerifier {

  /** Ed25519 public key of the PaperProxy release key (X.509, Base64). */
  static final String RELEASE_KEY = "MCowBQYDK2VwAyEA+986KgDkYfb+7MASF4xQE65KPoLyoHnfTqzKeyhpp6o=";

  private final PublicKey key;

  /**
   * Creates a verifier for the built-in release key.
   */
  public ReleaseVerifier() {
    this(RELEASE_KEY);
  }

  /**
   * Creates a verifier for another key, used by tests.
   *
   * @param base64Key the X.509 encoded Ed25519 public key
   */
  public ReleaseVerifier(final String base64Key) {
    try {
      this.key = KeyFactory.getInstance("Ed25519")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Key)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Invalid release key", e);
    }
  }

  /**
   * Verifies a release.
   *
   * @param jar the downloaded jar
   * @param sha512File content of the .sha512 file (hex, optionally followed by a file name)
   * @param signatureFile content of the .sig file (Base64 Ed25519 signature over the jar)
   * @return null if valid, otherwise the reason
   */
  public String verify(final byte[] jar, final String sha512File, final String signatureFile) {
    final String expected = sha512File.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
    final String actual;
    try {
      actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(jar));
    } catch (GeneralSecurityException e) {
      return "SHA-512 is not available";
    }
    if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
        actual.getBytes(StandardCharsets.US_ASCII))) {
      return "checksum mismatch";
    }
    try {
      final Signature signature = Signature.getInstance("Ed25519");
      signature.initVerify(key);
      signature.update(jar);
      if (!signature.verify(Base64.getDecoder().decode(signatureFile.trim()))) {
        return "invalid signature";
      }
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return "invalid signature (" + e.getMessage() + ")";
    }
    return null;
  }
}
