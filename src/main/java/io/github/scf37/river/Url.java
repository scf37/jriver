package io.github.scf37.river;

import java.util.regex.Pattern;

/** Backup url: {@code <driver>:<driver specific path>}. */
public record Url(String driver, String path) {
  private static final Pattern DRIVER = Pattern.compile("[A-Za-z0-9_-]+");

  public static Url parse(String s) {
    int i = s.indexOf(':');
    if (i <= 0 || !DRIVER.matcher(s.substring(0, i)).matches())
      throw new RiverException("illegal url: " + s + " (expected <driver>:<path>)");
    String p = s.substring(i + 1);
    while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
    if (p.isEmpty()) throw new RiverException("illegal url: " + s + " (empty path)");
    return new Url(s.substring(0, i), p);
  }

  @Override
  public String toString() {
    return driver + ":" + path;
  }
}
