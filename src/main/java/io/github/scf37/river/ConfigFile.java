package io.github.scf37.river;

import io.github.scf37.jpaq.libzpaq.Crypto;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Seal of a config file: the last line {@code mac: <salt>.<hash>}.
 *
 * <p>The hash covers all bytes in front of that line. Without a key it is SHA-256 and the salt is empty. With a
 * key (encrypted backup) it is HMAC-SHA256 under {@code scrypt(key, salt, N=131072, r=8, p=1)} with a fresh 16 byte
 * random salt per file. The mode comes from whether the caller has a key, never from the file.
 *
 * <p>A file is <em>well-formed</em> if it ends with a complete seal line; since a stored file is a prefix of what was
 * written, that means it was written completely. A well-formed file is <em>valid</em> if the hash verifies.
 */
final class ConfigFile {
  /** scrypt cost, jriver constants (same as the jpaq sequential format); tests lower it. */
  static int scryptN = Crypto.SEQUENTIAL_N;

  private static final Pattern SEAL = Pattern.compile("\nmac: ([0-9a-f]{32}|)\\.([0-9a-f]{64})\n\\z");
  private static final SecureRandom RANDOM = new SecureRandom();
  static final int MAX_SIZE = 16 << 20;

  /** A well-formed file: the covered bytes and the seal. */
  record Sealed(byte[] covered, String salt, String hash) {}

  private final byte[] key;                       // null: plain checksums
  private final Map<String, byte[]> derived = new HashMap<>();

  ConfigFile(String key) {
    this.key = key == null || key.isEmpty() ? null : key.getBytes(StandardCharsets.UTF_8);
  }

  boolean keyed() {
    return key != null;
  }

  /** The seal of a well-formed file, null if the file does not end with a complete seal line. */
  static Sealed wellFormed(byte[] file) {
    int from = Math.max(0, file.length - 128);
    String tail = new String(file, from, file.length - from, StandardCharsets.ISO_8859_1);
    Matcher m = SEAL.matcher(tail);
    if (!m.find()) return null;
    int end = from + m.start() + 1;  // includes the newline in front of 'mac:'
    return new Sealed(Arrays.copyOf(file, end), m.group(1), m.group(2));
  }

  private byte[] macKey(String saltHex) {
    return derived.computeIfAbsent(saltHex, s -> {
      byte[] out = new byte[32];
      byte[] salt = HexFormat.of().parseHex(s);
      Crypto.scrypt(key, key.length, salt, salt.length, scryptN, 8, 1, out, 32);
      return out;
    });
  }

  private byte[] hash(byte[] covered, String saltHex) {
    try {
      if (key == null) return MessageDigest.getInstance("SHA-256").digest(covered);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(macKey(saltHex), "HmacSHA256"));
      return mac.doFinal(covered);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** True if the seal is right for this key (or for no key). */
  boolean verify(Sealed s) {
    if (key == null != s.salt().isEmpty()) return false;
    byte[] expected = hash(s.covered(), s.salt());
    return MessageDigest.isEqual(HexFormat.of().formatHex(expected).getBytes(StandardCharsets.ISO_8859_1),
        s.hash().getBytes(StandardCharsets.ISO_8859_1));
  }

  /** The bytes of a file: the body (ends with a newline) and its seal line. */
  byte[] seal(String body) {
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    return seal(body, salt);
  }

  /** Same with a given salt (ignored without a key). */
  byte[] seal(String body, byte[] saltBytes) {
    if (!body.endsWith("\n")) body += "\n";
    byte[] covered = body.getBytes(StandardCharsets.UTF_8);
    String salt = key == null ? "" : HexFormat.of().formatHex(saltBytes);
    String line = "mac: " + salt + "." + HexFormat.of().formatHex(hash(covered, salt)) + "\n";
    byte[] tail = line.getBytes(StandardCharsets.ISO_8859_1);
    byte[] all = Arrays.copyOf(covered, covered.length + tail.length);
    System.arraycopy(tail, 0, all, covered.length, tail.length);
    return all;
  }
}
