package io.github.scf37.river;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names of the files of a backup. Only names of exactly these formats are ever accepted from a listing or from a
 * config, and only such names are passed to a driver or deleted.
 *
 * <pre>
 * config.&lt;revision&gt;.&lt;random&gt;.yaml                    at the root: commit marker of a revision
 * &lt;full backup&gt;/arc&lt;revision&gt;.&lt;random&gt;.jpaq        tome written by that revision
 * &lt;full backup&gt;/idx&lt;revision&gt;.&lt;random&gt;.jpaq        index of the full backup, written by that revision
 * </pre>
 *
 * The full backup is a folder named by its creation time, {@code yyyyMMddTHHmmssSSS}. The revision is zero padded to
 * 5 digits, the random part is 32 bits of lowercase hex.
 */
final class Names {
  private static final String FOLDER = "\\d{8}T\\d{9}";
  private static final Pattern FOLDER_NAME = Pattern.compile(FOLDER);
  private static final Pattern CONFIG = Pattern.compile("config\\.(\\d{5,9})\\.([0-9a-f]{8})\\.yaml");
  private static final Pattern DATA =
      Pattern.compile("(" + FOLDER + ")/(arc|idx)(\\d{5,9})\\.([0-9a-f]{8})\\.jpaq");
  private static final SecureRandom RANDOM = new SecureRandom();

  static final String CONFIG_PREFIX = "config.";

  /**
   * A recognized file name and the revision it belongs to; folder is the full backup of a tome or index, null for a
   * config.
   */
  record Name(String name, boolean config, String folder, boolean index, int revision) {}

  private Names() {}

  private static String random() {
    return HexFormat.of().toHexDigits(RANDOM.nextInt());
  }

  static String config(int revision) {
    return "config.%05d.%s.yaml".formatted(revision, random());
  }

  static String tome(String folder, int revision) {
    return "%s/arc%05d.%s.jpaq".formatted(folder, revision, random());
  }

  static String index(String folder, int revision) {
    return "%s/idx%05d.%s.jpaq".formatted(folder, revision, random());
  }

  /** The name if it is a config, tome or index name of the expected format, otherwise null. */
  static Name parse(String s) {
    Matcher m = CONFIG.matcher(s);
    if (m.matches()) return number(new Name(s, true, null, false, 0), m.group(1));
    m = DATA.matcher(s);
    if (m.matches()) return number(new Name(s, false, m.group(1), m.group(2).equals("idx"), 0), m.group(3));
    return null;
  }

  private static Name number(Name n, String digits) {
    try {
      return new Name(n.name(), n.config(), n.folder(), n.index(), Integer.parseInt(digits));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The full backup if the listing entry is the name of its folder (with or without a trailing slash), else null. */
  static String folder(String entry) {
    String e = entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
    return FOLDER_NAME.matcher(e).matches() ? e : null;
  }

  /** The name of a full backup, which is also its folder; throws if it is not of the expected format. */
  static String requireFolder(String s, String what) {
    if (s == null || !FOLDER_NAME.matcher(s).matches()) throw new RiverException(what + ": illegal name '" + s + "'");
    return s;
  }

  /** Same as {@link #parse} but throws: for a tome or index of the given full backup that comes from a config. */
  static Name require(String s, String folder, String what) {
    Name n = s == null ? null : parse(s);
    if (n == null || n.config() || !n.folder().equals(folder))
      throw new RiverException(what + ": illegal file name '" + s + "'");
    return n;
  }
}
