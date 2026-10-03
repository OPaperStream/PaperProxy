/*
 * Signs a PaperProxy release jar for the auto-updater.
 *
 * Usage: java tools/SignRelease.java proxy/build/libs/paperproxy-<version>.jar
 *
 * Writes <jar>.sha512 and <jar>.sig next to the jar. Upload all three files to the GitHub
 * release. The private key is read from ~/.config/paperproxy/release-signing-key.pem and must
 * never be committed or uploaded.
 */

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

public class SignRelease {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("Usage: java tools/SignRelease.java <jar>");
      System.exit(1);
    }
    Path jar = Path.of(args[0]);
    Path keyFile = Path.of(System.getProperty("user.home"), ".config/paperproxy/release-signing-key.pem");
    String pem = Files.readString(keyFile)
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "");
    PrivateKey key = KeyFactory.getInstance("Ed25519")
        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    byte[] bytes = Files.readAllBytes(jar);
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
    Files.writeString(Path.of(jar + ".sha512"), sha + "  " + jar.getFileName() + "\n");
    Signature signature = Signature.getInstance("Ed25519");
    signature.initSign(key);
    signature.update(bytes);
    Files.writeString(Path.of(jar + ".sig"),
        Base64.getEncoder().encodeToString(signature.sign()) + "\n");
    System.out.println("Signed " + jar.getFileName());
  }
}
