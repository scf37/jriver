package io.github.scf37.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.scf37.river.Fixture.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RiverTest {
  @TempDir Path tmp;
  Fixture f;

  @BeforeEach
  void setUp() throws IOException {
    f = new Fixture(tmp);
  }

  private static List<String> names(int n) {
    return IntStream.rangeClosed(1, n).mapToObj(i -> i + ".file").sorted().toList();
  }

  @Test
  void initPrintsExampleAndRefusesToOverwrite() throws IOException {
    Result r = f.river("init");
    assertEquals(0, r.rc());
    assertTrue(r.out().contains("keep_full_backup_count"));
    Path file = tmp.resolve("river.yaml");
    assertEquals(0, f.river("init", file.toString()).rc());
    assertEquals(r.out(), Files.readString(file));
    assertEquals(2, f.river("init", file.toString()).rc());
  }

  @Test
  void exampleConfigRoundTripsThroughCreateOnceHasPaths() throws IOException {
    String example = f.river("init").out().replace("paths: []", "paths: ['" + f.src + "']");
    f.create(example);
    Result r = f.river("config", f.url);
    assertEquals(0, r.rc());
    assertTrue(r.out().contains("method: '1'"), r.out());
    assertTrue(r.out().contains("keep_incremental_backup_count: 30"), r.out());
    assertFalse(r.out().contains("generations"), "config shows configuration only");
  }

  @Test
  void createValidatesAndRefusesExisting() throws IOException {
    assertEquals(2, f.riverStdin("paths: []\n", "create", f.url).rc());
    assertEquals(2, f.riverStdin("paths: ['/x']\nkeep_full_backup_count: 0\n", "create", f.url).rc());
    assertEquals(2, f.riverStdin("paths: ['/x']\nencrypted: yes please\n", "create", f.url).rc());
    assertEquals(2, f.river("create", f.url, tmp.resolve("missing.yaml").toString()).rc());
    assertEquals(0, f.riverStdin("paths: ['/x']\n", "create", f.url).rc());
    Result again = f.riverStdin("paths: ['/x']\n", "create", f.url);
    assertEquals(2, again.rc());
    assertTrue(again.err().contains("already exists"), again.err());
  }

  @Test
  void unknownDriverAndBadArguments() {
    Result r = f.river("list", "nosuchdriver:/x");
    assertEquals(2, r.rc());
    assertTrue(r.err().contains("unknown driver"), r.err());
    assertEquals(2, f.river("list", "no-colon").rc());
    assertEquals(2, f.river("frobnicate").rc());
    assertEquals(2, f.river("backup").rc());
    assertEquals(0, f.river("help").rc());
  }

  @Test
  void backupsRollAndEveryVersionRestores() throws IOException {
    f.create(f.config("keep_full_backup_count: 5\n").replace("keep_full_backup_count: 3\n", ""));
    for (int n = 1; n <= 7; n++) {
      f.write(n + ".file", String.valueOf(n));
      f.backup();
    }
    List<String> versions = f.versions();
    assertEquals(7, versions.size());
    // keep_incremental_backup_count 2: a full backup and two incrementals per generation
    assertEquals(3, versions.stream().filter(v -> v.endsWith(":1")).count() + 0 * versions.size());
    assertEquals(List.of(3L, 3L, 1L),
        versions.stream().map(v -> v.substring(0, v.lastIndexOf(':'))).distinct()
            .map(g -> versions.stream().filter(v -> v.startsWith(g + ":")).count()).toList());

    for (int n = 1; n <= 7; n++) assertEquals(names(n), f.restoredFiles(versions.get(n - 1)), "version " + (n - 1));
    for (int n = 7; n >= 1; n--) assertEquals(names(n), f.restoredFiles(versions.get(n - 1)), "again " + n);
    assertEquals(names(7), f.restoredFiles("latest"));

    // one tome per version, one index per generation, nothing else (no leftovers)
    try (var s = Files.list(f.remote)) {
      assertEquals(5, s.count(), "lock, river.conf and 3 generations");
    }
    for (String g : versions.stream().map(v -> v.substring(0, v.lastIndexOf(':'))).distinct().toList()) {
      try (var s = Files.list(f.remote.resolve(g))) {
        List<String> files = s.map(p -> p.getFileName().toString()).toList();
        assertEquals(1, files.stream().filter(n -> n.startsWith("idx-")).count(), files.toString());
        assertTrue(files.stream().noneMatch(n -> n.contains(".tmp")), files.toString());
      }
    }
  }

  @Test
  void oldestGenerationIsDeletedBeyondKeepFull() throws IOException {
    f.create(f.config("").replace("keep_full_backup_count: 3", "keep_full_backup_count: 2"));
    for (int n = 1; n <= 7; n++) {
      f.write(n + ".file", String.valueOf(n));
      f.backup();
    }
    List<String> versions = f.versions();
    assertEquals(4, versions.size(), "3 + 1 versions of the two newest generations");
    String first = versions.get(0).substring(0, versions.get(0).lastIndexOf(':'));
    long dirs;
    try (var s = Files.list(f.remote)) {
      dirs = s.filter(Files::isDirectory).count();
    }
    assertEquals(2, dirs);
    assertEquals(names(7), f.restoredFiles("latest"));
    assertEquals(names(4), f.restoredFiles(versions.get(0)), "oldest kept version " + first);
  }

  @Test
  void restoreInPlace() throws IOException {
    f.create(f.config(""));
    f.write("a.file", "A");
    f.write("sub dir/b c.file", "B");
    f.backup();
    f.write("a.file", "changed");
    Files.delete(f.src.resolve("sub dir/b c.file"));
    assertEquals(0, f.river("restore", f.url).rc());
    assertEquals("A", Files.readString(f.src.resolve("a.file")));
    assertEquals("B", Files.readString(f.src.resolve("sub dir/b c.file")));
  }

  @Test
  void restoreFilesByPattern() throws IOException {
    f.create(f.config(""));
    f.write("keep/1.txt", "1");
    f.write("other/2.txt", "2");
    f.backup();
    Path to = tmp.resolve("part");
    Result r = f.river("restore", f.url, "latest", "--to", to.toString(), "--files", f.src + "/keep");
    assertEquals(0, r.rc(), r.toString());
    assertEquals(List.of("keep/1.txt"), f.files(to));
    Path inPlace = f.src.resolve("keep/1.txt");
    Files.delete(inPlace);
    assertEquals(0, f.river("restore", f.url, "latest", "--files", f.src + "/keep").rc());
    assertEquals("1", Files.readString(inPlace));
    assertTrue(Files.notExists(f.src.resolve("other/2.txt")) == false, "untouched");
  }

  @Test
  void listFilesOfAVersion() throws IOException {
    f.create(f.config(""));
    f.write("a.file", "A");
    f.backup();
    f.write("b.file", "B");
    f.backup();
    List<String> v = f.versions();
    Result r1 = f.river("list", f.url, "--files", v.get(0));
    Result r2 = f.river("list", f.url, "--files", "latest");
    assertEquals(List.of(f.src + "/a.file"), r1.out().lines().toList(), r1.toString());
    assertEquals(List.of(f.src + "/a.file", f.src + "/b.file"), r2.out().lines().sorted().toList(), r2.toString());
  }

  @Test
  void backupWithoutChangesAddsNoVersion() throws IOException {
    f.create(f.config(""));
    f.write("a.file", "A");
    f.backup();
    f.backup();
    f.backup();
    assertEquals(1, f.versions().size());
    f.write("b.file", "B");
    f.backup();
    assertEquals(2, f.versions().size());
    try (var s = Files.list(f.remote)) {
      assertEquals(3, s.count(), "lock, river.conf, one generation: " + f.remote);
    }
    try (var s = Files.list(f.remote.resolve(f.versions().get(0).split(":")[0]))) {
      assertEquals(3, s.count(), "two tomes and the index");
    }
  }

  @Test
  void exclude() throws IOException {
    f.create(f.config("exclude: ['*.tmp']\n"));
    f.write("a.keep", "1");
    f.write("b.tmp", "2");
    f.write("sub/c.other", "3");
    f.write("sub/d.tmp", "4");
    f.backup();
    assertEquals(List.of("a.keep", "sub/c.other"), f.restoredFiles("latest"));
  }

  @Test
  void includeOnly() throws IOException {
    f.create(f.config("include_only: ['*.keep']\n"));
    f.write("a.keep", "1");
    f.write("b.tmp", "2");
    f.write("sub/c.keep", "3");
    f.backup();
    assertEquals(List.of("a.keep", "sub/c.keep"), f.restoredFiles("latest"));
  }

  @Test
  void deletedFilesAreNotRestoredInLaterVersions() throws IOException {
    f.create(f.config(""));
    f.write("a.file", "A");
    f.write("b.file", "B");
    f.backup();
    Files.delete(f.src.resolve("b.file"));
    f.backup();
    List<String> v = f.versions();
    assertEquals(List.of("a.file", "b.file"), f.restoredFiles(v.get(0)));
    assertEquals(List.of("a.file"), f.restoredFiles(v.get(1)));
  }

  @Test
  void configUpdateKeepsState() throws IOException {
    f.create(f.config(""));
    f.write("a.file", "A");
    f.backup();
    List<String> before = f.versions();
    String updated = f.config("").replace("keep_incremental_backup_count: 2", "keep_incremental_backup_count: 9");
    assertEquals(0, f.riverStdin(updated, "config", f.url, "-").rc());
    assertTrue(f.river("config", f.url).out().contains("keep_incremental_backup_count: 9"));
    assertEquals(before, f.versions());
    assertEquals(List.of("a.file"), f.restoredFiles("latest"));
    // encryption cannot be switched on a backup that has data
    f.env.put("river_key", "k");
    assertEquals(2, f.riverStdin(f.config("encrypted: true\n"), "config", f.url, "-").rc());
  }

  @Test
  void encryptedBackup() throws IOException {
    f.env.put("river_key", "hello");
    f.create(f.config("encrypted: true\n"));
    f.write("a.file", "secret content");
    f.backup();
    f.write("b.file", "more");
    f.backup();
    assertEquals(List.of("a.file", "b.file"), f.restoredFiles("latest"));
    assertEquals(0, f.river("verify", f.url).rc());
    // nothing readable in the archive files
    try (var s = Files.walk(f.remote)) {
      for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
        if (p.getFileName().toString().endsWith(".jpaq"))
          assertFalse(Files.readString(p, java.nio.charset.StandardCharsets.ISO_8859_1).contains("secret content"));
      }
    }
    Path keyFile = tmp.resolve("key");
    Files.writeString(keyFile, "hello\n");
    f.env.remove("river_key");
    assertEquals(2, f.river("restore", f.url, "latest", "--to", tmp.resolve("x").toString()).rc(), "no key");
    f.env.put("river_key_file", keyFile.toString());
    assertEquals(0, f.river("verify", f.url).rc(), "key from file");
    f.env.remove("river_key_file");
    f.env.put("river_key", "wrong");
    Result bad = f.river("verify", f.url);
    assertNotEquals(0, bad.rc(), "wrong key: " + bad);
    assertNotEquals(0, f.river("backup", f.url).rc(), "wrong key backup");
    assertEquals(2, f.river("backup", f.url).rc());
  }

  @Test
  void keyWithoutEncryptionIsRejected() throws IOException {
    f.create(f.config(""));
    f.env.put("river_key", "k");
    Result r = f.river("backup", f.url);
    assertEquals(2, r.rc());
    assertTrue(r.err().contains("not encrypted"), r.err());
    f.env.remove("river_key");
    Result noKey = f.riverStdin(f.config("encrypted: true\n"), "create", "local:" + tmp.resolve("r2"));
    assertEquals(2, noKey.rc());
    assertTrue(noKey.err().contains("river_key"), noKey.err());
  }

  @Test
  void unreadableFileGivesExitCode1ButBackupStillWorks() throws IOException {
    f.create(f.config(""));
    f.write("a.file", "A");
    f.write("secret.file", "S");
    java.nio.file.Files.setPosixFilePermissions(f.src.resolve("secret.file"), java.util.Set.of());
    try {
      if (Files.isReadable(f.src.resolve("secret.file"))) return;  // running as root: cannot test
      Result r = f.river("backup", f.url);
      assertEquals(1, r.rc(), r.toString());
      assertEquals(1, f.versions().size());
      assertEquals(List.of("a.file"), f.restoredFiles("latest"));
    } finally {
      Files.setPosixFilePermissions(f.src.resolve("secret.file"),
          java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ));
    }
  }

  @Test
  void deleteRemovesEverything() throws IOException {
    f.create(f.config(""));
    f.write("a.file", "A");
    f.backup();
    assertEquals(2, f.river("delete", f.url).rc(), "needs --yes");
    assertTrue(Files.exists(f.remote.resolve("river.conf")));
    assertEquals(0, f.river("delete", f.url, "--yes").rc());
    assertEquals(List.of(), f.files(f.remote));
    assertEquals(2, f.river("list", f.url).rc());
  }

  @Test
  void restoreOfUnknownVersionFails() throws IOException {
    f.create(f.config(""));
    assertEquals(2, f.river("restore", f.url).rc(), "no versions yet");
    f.write("a.file", "A");
    f.backup();
    String g = f.versions().get(0).split(":")[0];
    assertEquals(2, f.river("restore", f.url, g + ":9").rc());
    assertEquals(2, f.river("restore", f.url, "nope:1").rc());
    assertEquals(2, f.river("restore", f.url, "garbage").rc());
  }

  @Test
  void manyFilesAndLargerData() throws IOException {
    f.create(f.config("method: 2\n"));
    java.util.Random rnd = new java.util.Random(1);
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      byte[] b = new byte[rnd.nextInt(200_000)];
      rnd.nextBytes(b);
      Path p = f.src.resolve("d" + (i % 5) + "/f" + i + ".bin");
      Files.createDirectories(p.getParent());
      Files.write(p, b);
      expected.add("d" + (i % 5) + "/f" + i + ".bin");
    }
    f.backup();
    Path to = tmp.resolve("big");
    assertEquals(0, f.river("restore", f.url, "latest", "--to", to.toString()).rc());
    Path restored = to.resolve(f.src.toString().substring(1));
    for (String e : expected) assertEquals(-1, Files.mismatch(f.src.resolve(e), restored.resolve(e)), e);
  }
}
