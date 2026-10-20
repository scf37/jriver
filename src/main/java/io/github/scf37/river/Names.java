package io.github.scf37.river;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names of the files at the backup root. Only names of exactly these formats are ever accepted from a
 * listing or from a config, and only such names are passed to a driver or deleted.
 *
 * <pre>
 * config.&lt;revision&gt;.&lt;random&gt;.yaml    commit marker of a revision
 * arc&lt;revision&gt;.&lt;random&gt;.jpaq         tome written by that revision
 * idx&lt;revision&gt;.&lt;random&gt;.jpaq         index written by that revision
 * </pre>
 *
 * The revision is zero padded to 5 digits, the random part is 32 bits of lowercase hex.
 */
final class Names {
  private static final Pattern CONFIG = Pattern.compile("config\\.(\\d{5,9})\\.([0-9a-f]{8})\\.yaml");
  private static final Pattern DATA = Pattern.compile("(arc|idx)(\\d{5,9})\\.([0-9a-f]{8})\\.jpaq");
  private static final SecureRandom RANDOM = new SecureRandom();

  static final String CONFIG_PREFIX = "config.";
  static final String TOME_PREFIX = "arc";
  static final String INDEX_PREFIX = "idx";

  /** A recognized file name and the revision it belongs to. */
  record Name(String name, boolean config, int revision) {}

  private Names() {}

  private static String random() {
    return HexFormat.of().toHexDigits(RANDOM.nextInt());
  }

  static String config(int revision) {
    return "config.%05d.%s.yaml".formatted(revision, random());
  }

  static String tome(int revision) {
    return "arc%05d.%s.jpaq".formatted(revision, random());
  }

  static String index(int revision) {
    return "idx%05d.%s.jpaq".formatted(revision, random());
  }

  /** The name if it is a config, tome or index name of the expected format, otherwise null. */
  static Name parse(String s) {
    Matcher m = CONFIG.matcher(s);
    if (m.matches()) return number(s, true, m.group(1));
    m = DATA.matcher(s);
    if (m.matches()) return number(s, false, m.group(2));
    return null;
  }

  private static Name number(String s, boolean config, String digits) {
    try {
      return new Name(s, config, Integer.parseInt(digits));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Same as {@link #parse} but throws: for names that come from a config. */
  static Name require(String s, String what) {
    Name n = s == null ? null : parse(s);
    if (n == null || n.config()) throw new RiverException(what + ": illegal file name '" + s + "'");
    return n;
  }
}
