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
    g.index = "idx00002.0a1b2c3d.jpaq";
    g.tomes.add("arc00001.11111111.jpaq");
    g.tomes.add("arc00002.22222222.jpaq");
    c.revision = 2;
    g.versions.add(new Conf.Version(1, "2026-01-01T00:00:00Z"));
    g.versions.add(new Conf.Version(2, "2026-01-02T00:00:00Z"));
    c.generations.add(g);

    Conf d = Conf.parse(c.dump());
    assertEquals(c.dump(), d.dump());
    assertEquals(List.of("/etc", "/home/me with space"), d.paths);
    assertEquals("2", d.method);
    assertEquals(2, d.generation("g1").versions.size());
    assertEquals("idx00002.0a1b2c3d.jpaq", d.generation("g1").index);
    assertEquals(List.of("arc00001.11111111.jpaq", "arc00002.22222222.jpaq"), d.generation("g1").tomes);
    assertEquals(2, d.revision);
  }

  @Test
  void fileNamesInTheConfigAreChecked() {
    String ok = "paths: [/a]\nrevision: 1\ngenerations:\n- name: g\n  index: idx00001.0a1b2c3d.jpaq\n  tomes: [%s]\n  versions: []\n";
    Conf.parse(ok.formatted("arc00001.0a1b2c3d.jpaq"));
    for (String bad : List.of("../arc00001.0a1b2c3d.jpaq", "config.00001.0a1b2c3d.yaml", "x; rm -rf ~", "'a b'"))
      assertThrows(RiverException.class, () -> Conf.parse(ok.formatted(bad)), bad);
    assertThrows(RiverException.class, () -> Conf.parse("paths: [/a]\n"), "revision is required");
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
