package io.github.scf37.river;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The two small mutable files at the backup root: {@code river.conf} and {@code lock}. */
final class Remote {
  static final String CONF = "river.conf";
  static final String LOCK = "lock";

  final Driver driver;
  final Url url;

  Remote(Driver driver, Url url) {
    this.driver = driver;
    this.url = url;
  }

  boolean confExists() {
    try {
      driver.download(CONF);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  Conf readConf() {
    try {
      return Conf.parse(new String(driver.download(CONF), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new RiverException("cannot read " + CONF + " at " + url + " (wrong url? not created?): " + e.getMessage(), e);
    }
  }

  void writeConf(Conf conf) throws IOException {
    driver.upload(CONF, conf.dump().getBytes(StandardCharsets.UTF_8));
  }

  /** Lock file contents: {@code <uuid> <expires, unix seconds>}. */
  record LockEntry(String uuid, long expires) {
    static LockEntry parse(byte[] data) throws IOException {
      String[] p = new String(data, StandardCharsets.UTF_8).trim().split("\\s+");
      if (p.length != 2) throw new IOException("unparsable lock file");
      try {
        return new LockEntry(p[0], Long.parseLong(p[1]));
      } catch (NumberFormatException e) {
        throw new IOException("unparsable lock file", e);
      }
    }

    byte[] bytes() {
      return (uuid + " " + expires + "\n").getBytes(StandardCharsets.UTF_8);
    }
  }

  LockEntry readLock() throws IOException {
    return LockEntry.parse(driver.download(LOCK));
  }

  void writeLock(LockEntry e) throws IOException {
    driver.upload(LOCK, e.bytes());
  }
}
