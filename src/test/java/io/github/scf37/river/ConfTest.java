package io.github.scf37.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfTest {
  @Test
  void roundTripKeepsConfigAndState() {
    Conf c = new Conf();
    c.paths = List.of("/etc", "/home/me with space");
    c.exclude = List.of("*.tmp");
    c.method = "2";
    c.keepIncrementalBackupCount = 5;
    c.keepFullBackupCount = 2;
    Conf.Generation g = new Conf.Generation("g1");
    g.index = "g1/idx-1-abcd.jpaq";
    g.versions.add(new Conf.Version(1, "2026-01-01T00:00:00Z"));
    g.versions.add(new Conf.Version(2, "2026-01-02T00:00:00Z"));
    c.generations.add(g);

    Conf d = Conf.parse(c.dump());
    assertEquals(c.dump(), d.dump());
    assertEquals(List.of("/etc", "/home/me with space"), d.paths);
    assertEquals("2", d.method);
    assertEquals(2, d.generation("g1").versions.size());
    assertEquals("g1/idx-1-abcd.jpaq", d.generation("g1").index);
    assertEquals(c.stateFingerprint(), d.stateFingerprint());
  }

  @Test
  void stateFingerprintChangesWithState() {
    Conf c = new Conf();
    String before = c.stateFingerprint();
    c.generations.add(new Conf.Generation("g"));
    assertEquals(false, before.equals(c.stateFingerprint()));
  }

  @Test
  void numericMethodIsKeptAsText() {
    assertEquals("1", Conf.parseConfig("paths: [/a]\nmethod: 1\n", "t").method);
    assertEquals("x4,0", Conf.parseConfig("paths: [/a]\nmethod: x4,0\n", "t").method);
  }

  @Test
  void defaultsAndValidation() {
    Conf c = Conf.parseConfig("paths: [/a]\n", "t");
    assertEquals("1", c.method);
    assertEquals(30, c.keepIncrementalBackupCount);
    assertEquals(3, c.keepFullBackupCount);
    assertThrows(RiverException.class, () -> Conf.parseConfig("paths: [1]\n", "t"));
    assertThrows(RiverException.class, () -> Conf.parseConfig("paths: /a\n", "t"));
    assertThrows(RiverException.class, () -> Conf.parseConfig("keep_full_backup_count: x\n", "t"));
    assertThrows(RiverException.class, () -> Conf.parseConfig("keep_incremental_backup_count: -1\n", "t"));
    assertThrows(RiverException.class, () -> Conf.parseConfig("[1, 2]", "t"));
    assertThrows(RiverException.class, () -> Conf.parseConfig("a: [", "t"));
  }

  @Test
  void urlParsing() {
    assertEquals(new Url("ssh", "me@host:/a/b"), Url.parse("ssh:me@host:/a/b/"));
    assertEquals(new Url("s3", "//bucket/x"), Url.parse("s3://bucket/x"));
    assertThrows(RiverException.class, () -> Url.parse("nodriver"));
    assertThrows(RiverException.class, () -> Url.parse("../x:/y"));
    assertThrows(RiverException.class, () -> Url.parse("ssh:"));
  }
}
