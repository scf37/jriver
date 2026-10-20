package io.github.scf37.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.scf37.river.Fixture.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Failures at every step of a backup: the last committed version always stays intact. */
class FaultTest {
  @TempDir Path tmp;
  Fixture f;
  String url;

  @BeforeEach
  void setUp() throws IOException {
    f = new Fixture(tmp);
    f.addFlakyDriver();
    url = "flaky:" + f.remote;
    f.create(f.config(""));  // through the 'local' url; same directory
    f.write("1.file", "1");
    assertEquals(0, f.river("backup", url).rc());
  }

  private List<String> versions() {
    return f.river("list", url).out().lines().map(l -> l.substring(0, l.indexOf('\t'))).toList();
  }

  private List<String> restoredLatest() throws IOException {
    Path to = tmp.resolve("r-" + System.nanoTime());
    Result r = f.river("restore", url, "latest", "--to", to.toString());
    assertEquals(0, r.rc(), r.toString());
    return f.files(to.resolve(f.src.toString().substring(1)));
  }

  private List<String> names(String prefix) throws IOException {
    return f.remoteNames().stream().filter(n -> n.substring(n.lastIndexOf('/') + 1).startsWith(prefix)).toList();
  }

  private String newestConfig() throws IOException {
    List<String> c = names("config.");
    return c.get(c.size() - 1);
  }

  @Test
  void failedTomeUploadChangesNothingAndNextBackupWorks() throws IOException {
    f.write("2.file", "2");
    f.failWhen("upload:*/arc*");
    Result r = f.river("backup", url);
    assertNotEquals(0, r.rc(), r.toString());
    assertEquals(1, versions().size());
    assertEquals(List.of("1.file"), restoredLatest());
    f.failNever();
    assertEquals(0, f.river("backup", url).rc());
    assertEquals(2, versions().size());
    assertEquals(List.of("1.file", "2.file"), restoredLatest());
  }

  @Test
  void failedIndexUploadChangesNothing() throws IOException {
    f.write("2.file", "2");
    f.failWhen("upload:*/idx*");
    assertNotEquals(0, f.river("backup", url).rc());
    assertEquals(1, versions().size());
    assertEquals(List.of("1.file"), restoredLatest());
    f.failNever();
    assertEquals(0, f.river("backup", url).rc());
    assertEquals(List.of("1.file", "2.file"), restoredLatest());
  }

  @Test
  void failedCommitLeavesPreviousVersionIntactAndOrphansAreCleanedLater() throws IOException {
    f.write("2.file", "2");
    f.failWhen("upload:config.*");
    Result r = f.river("backup", url);
    assertNotEquals(0, r.rc(), r.toString());
    assertEquals(1, versions().size());
    assertEquals(List.of("1.file"), restoredLatest());
    f.failNever();
    f.write("3.file", "3");
    assertEquals(0, f.river("backup", url).rc());
    assertEquals(2, versions().size());
    assertEquals(List.of("1.file", "2.file", "3.file"), restoredLatest());
    // the orphans carry the revision that was committed afterwards, so they stay for one more revision
    assertEquals(3, names("arc").size(), names("arc").toString());
    assertEquals(3, names("idx").size(), names("idx").toString());
    f.write("4.file", "4");
    assertEquals(0, f.river("backup", url).rc());
    // the orphans (revision 3) are gone; the index of the previous revision stays as a fallback
    assertEquals(3, names("arc").size(), names("arc").toString());
    assertEquals(2, names("idx").size(), names("idx").toString());
    assertEquals(List.of("1.file", "2.file", "3.file", "4.file"), restoredLatest());
  }

  @Test
  void tornConfigIsIgnoredAndCleanedUp() throws IOException {
    String latest = newestConfig();
    byte[] bytes = Files.readAllBytes(f.remote.resolve(latest));
    String torn = "config.00003.deadbeef.yaml";
    Files.write(f.remote.resolve(torn), Arrays.copyOf(bytes, bytes.length - 20));
    assertEquals(1, versions().size());
    f.write("2.file", "2");
    assertEquals(0, f.river("backup", url).rc());
    assertEquals(2, versions().size());
    assertTrue(Files.exists(f.remote.resolve(torn)), "revision 3 is not older than the latest yet");
    f.write("3.file", "3");
    assertEquals(0, f.river("backup", url).rc());
    assertTrue(Files.notExists(f.remote.resolve(torn)), "removed by cleanup of revision 4");
    assertEquals(List.of("1.file", "2.file", "3.file"), restoredLatest());
  }

  @Test
  void failedOldFileCleanupIsOnlyAWarning() throws IOException {
    f.write("2.file", "2");
    f.failWhen("delete:*");
    Result r = f.river("backup", url);
    assertEquals(0, r.rc(), r.toString());
    assertTrue(r.err().contains("warning"), r.err());
    assertEquals(2, versions().size());
    f.failNever();
    assertEquals(List.of("1.file", "2.file"), restoredLatest());
  }

  @Test
  void failedListingNeverLooksLikeAnEmptyBackup() throws IOException {
    f.failWhen("list:*");
    f.write("2.file", "2");
    List<String> before = f.remoteNames();
    assertNotEquals(0, f.river("backup", url).rc());
    assertNotEquals(0, f.river("list", url).rc());
    assertNotEquals(0, f.riverStdin(f.config(""), "create", url).rc());
    f.failNever();
    assertEquals(before, f.remoteNames());
  }

  @Test
  void failedDownloadOfIndexFailsRestoreAndBackup() throws IOException {
    f.failWhen("download:*/idx*");
    assertNotEquals(0, f.river("restore", url, "latest", "--to", tmp.resolve("x").toString()).rc());
    f.write("2.file", "2");
    assertNotEquals(0, f.river("backup", url).rc());
    f.failNever();
    assertEquals(1, versions().size());
    assertEquals(List.of("1.file"), restoredLatest());
  }

  @Test
  void unreadableIndexNeverLetsBackupOverwriteCommittedTomes() throws IOException {
    f.write("2.file", "2");
    assertEquals(0, f.river("backup", url).rc());
    List<String> before = f.remoteNames();
    f.write("3.file", "3");
    for (String how : List.of("download", "vanished")) {
      Path idx = f.remote.resolve(names("idx").get(names("idx").size() - 1));  // the current one
      Path saved = tmp.resolve("idx.saved");
      if (how.equals("download")) f.failWhen("download:*/idx*");
      else Files.move(idx, saved);
      Result r = f.river("backup", url);
      f.failNever();
      if (how.equals("vanished")) Files.move(saved, idx);
      assertNotEquals(0, r.rc(), how + ": " + r);
      assertEquals(before, f.remoteNames().stream().filter(before::contains).toList(), how + ": nothing committed was touched");
      assertEquals(before.stream().filter(n -> n.startsWith("config.")).toList(), names("config.").stream()
          .filter(before::contains).toList(), how + ": configs untouched");
      assertEquals(2, versions().size(), how);
    }
    assertEquals(List.of("1.file", "2.file"), restoredLatest());
  }

  @Test
  void indexThatDisagreesWithConfigIsRefused() throws IOException {
    f.write("2.file", "2");
    assertEquals(0, f.river("backup", url).rc());
    String latest = newestConfig();
    Conf c = Conf.parse(new String(ConfigFile.wellFormed(Files.readAllBytes(f.remote.resolve(latest))).covered(),
        java.nio.charset.StandardCharsets.UTF_8));
    c.fullBackups.get(0).versions.remove(1);  // config forgot version 2, index still has it
    c.revision++;
    Files.write(f.remote.resolve(Names.config(c.revision)), new ConfigFile(null).seal(c.dump()));
    List<String> tomes = names("arc");
    f.write("3.file", "3");
    Result r = f.river("backup", url);
    assertNotEquals(0, r.rc(), r.toString());
    assertTrue(r.err().contains("config says"), r.err());
    assertEquals(tomes, names("arc"), "no tome written or removed");
  }

  @Test
  void backupThatIsOvertakenDoesNotCommit() throws IOException {
    f.write("2.file", "2");
    // while the index is uploaded, another run commits the same revision
    Conf rival = Conf.parse(new String(ConfigFile.wellFormed(Files.readAllBytes(f.remote.resolve(newestConfig())))
        .covered(), java.nio.charset.StandardCharsets.UTF_8));
    rival.revision = 3;
    Path staged = tmp.resolve("rival.yaml");
    Files.write(staged, new ConfigFile(null).seal(rival.dump()));
    f.hookWhen("upload:*/idx*", "cp '" + staged + "' \"$R/config.00003.cafebabe.yaml\"");
    Result r = f.river("backup", url);
    f.failNever();
    assertNotEquals(0, r.rc(), r.toString());
    assertTrue(r.err().contains("someone else"), r.err());
    assertEquals(1, versions().size());
    assertEquals(List.of("1.file"), restoredLatest());
  }

  @Test
  void twoValidConfigsOfTheLatestRevisionBothWork() throws IOException {
    f.write("2.file", "2");
    assertEquals(0, f.river("backup", url).rc());
    String latest = newestConfig();
    Files.copy(f.remote.resolve(latest), f.remote.resolve("config.00003.cafebabe.yaml"));
    assertEquals(2, versions().size());
    f.write("3.file", "3");
    assertEquals(0, f.river("backup", url).rc());
    assertEquals(List.of("1.file", "2.file", "3.file"), restoredLatest());
    f.write("4.file", "4");
    assertEquals(0, f.river("backup", url).rc());
    // the competitor's config is older than the previous revision by now
    assertEquals(2, names("config.").size(), names("config.").toString());
  }

  @Test
  void backupRunningOnThisMachineIsRefused() throws IOException {
    try (LocalLock held = LocalLock.acquire(java.nio.file.Path.of(f.env.get("river_lock_dir")), Url.parse(url))) {
      f.write("2.file", "2");
      Result r = f.river("backup", url);
      assertEquals(3, r.rc(), r.toString());
    }
    assertEquals(0, f.river("backup", url).rc());
  }

  @Test
  void deleteCanBeRepeatedAfterAFailure() throws IOException {
    f.failWhen("delete:*");
    assertNotEquals(0, f.river("delete", url, "--yes").rc());
    assertTrue(names("config.").size() > 0, "configs stay so that delete can be repeated");
    f.failNever();
    assertEquals(0, f.river("delete", url, "--yes").rc());
    assertEquals(List.of(), f.remoteNames());
  }
}
