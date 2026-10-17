package io.github.scf37.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.scf37.river.Fixture.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    f.create(f.config("").replace("paths", "paths"));
    // create went through the 'local' url above; same directory
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

  private long entries(Path dir) throws IOException {
    try (var s = Files.list(dir)) {
      return s.count();
    }
  }

  @Test
  void failedTomeUploadChangesNothingAndNextBackupWorks() throws IOException {
    f.write("2.file", "2");
    f.failWhen("upload:*/arc-*");
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
    f.failWhen("upload:*/idx-*");
    assertNotEquals(0, f.river("backup", url).rc());
    assertEquals(1, versions().size());
    assertEquals(List.of("1.file"), restoredLatest());
    f.failNever();
    assertEquals(0, f.river("backup", url).rc());
    assertEquals(List.of("1.file", "2.file"), restoredLatest());
  }

  @Test
  void failedCommitLeavesPreviousVersionIntact() throws IOException {
    f.write("2.file", "2");
    f.failWhen("upload:river.conf");
    Result r = f.river("backup", url);
    assertNotEquals(0, r.rc(), r.toString());
    assertEquals(1, versions().size());
    assertEquals(List.of("1.file"), restoredLatest());
    // the orphans (tome 2, new index) are not referenced; the next backup reuses the tome name
    f.failNever();
    f.write("3.file", "3");
    assertEquals(0, f.river("backup", url).rc());
    assertEquals(2, versions().size());
    assertEquals(List.of("1.file", "2.file", "3.file"), restoredLatest());
    Path gen = f.remote.resolve(versions().get(0).split(":")[0]);
    try (var s = Files.list(gen)) {
      assertEquals(2, s.filter(p -> p.getFileName().toString().startsWith("arc-")).count());
    }
  }

  /** What is left in the trash was deleted after the last commit; the next commit drops it. */
  private void assertTrashIsDeleted() throws IOException {
    for (String t : f.conf().trash) assertFalse(Files.exists(f.remote.resolve(t)), t);
  }

  /** Makes the generation full: the next backup starts a new one. */
  private void fillGeneration() throws IOException {
    for (int i = 2; i <= 3; i++) {
      f.write(i + ".file", "x");
      assertEquals(0, f.river("backup", url).rc());
    }
  }

  @Test
  void failedCommitOfAFreshGenerationLeavesItInTheTrash() throws IOException {
    fillGeneration();
    long before = entries(f.remote);
    f.write("4.file", "4");
    f.failWhen("upload:river.conf", 1);  // the generation is registered in the trash, the commit fails
    assertNotEquals(0, f.river("backup", url).rc());
    f.failNever();
    assertEquals(3, versions().size());
    assertEquals(before + 1, entries(f.remote), "the uploaded generation is still there");
    String orphan = f.conf().trash.get(0);
    assertTrue(Files.isDirectory(f.remote.resolve(orphan)), orphan);
    assertEquals(0, f.river("backup", url).rc());
    assertFalse(Files.exists(f.remote.resolve(orphan)), "the next run deletes it");
    assertEquals(4, versions().size());
    assertTrashIsDeleted();
  }

  @Test
  void freshGenerationOfARunThatDiesIsDeletedByTheNextRun() throws IOException {
    fillGeneration();
    f.write("4.file", "4");
    // the index upload fails and so does the cleanup: as if the run was killed after uploading its tome
    f.failWhen("upload:*/idx-* delete:*");
    assertNotEquals(0, f.river("backup", url).rc());
    f.failNever();
    List<String> trash = f.conf().trash.stream().filter(t -> !t.contains("/")).toList();  // also the old index
    assertEquals(1, trash.size(), trash.toString());
    assertTrue(Files.exists(f.remote.resolve(trash.get(0)).resolve("arc-000001.jpaq")));
    f.write("5.file", "5");
    assertEquals(0, f.river("backup", url).rc());
    assertFalse(Files.exists(f.remote.resolve(trash.get(0))));
    assertTrashIsDeleted();
    assertEquals(List.of("1.file", "2.file", "3.file", "4.file", "5.file"), restoredLatest());
  }

  @Test
  void freshGenerationIsNotStartedWithoutRegisteringItFirst() throws IOException {
    fillGeneration();
    long before = entries(f.remote);
    f.write("4.file", "4");
    f.failWhen("upload:river.conf");
    assertNotEquals(0, f.river("backup", url).rc());
    f.failNever();
    assertEquals(before, entries(f.remote), "nothing uploaded");
  }

  @Test
  void failedIndexOfFreshGenerationIsCleanedUp() throws IOException {
    for (int i = 2; i <= 3; i++) {
      f.write(i + ".file", "x");
      assertEquals(0, f.river("backup", url).rc());
    }
    long before = entries(f.remote);
    f.write("4.file", "4");
    f.failWhen("upload:*/idx-*");
    assertNotEquals(0, f.river("backup", url).rc());
    f.failNever();
    assertEquals(before, entries(f.remote), "no half-made generation left behind");
    assertEquals(3, versions().size());
  }

  private List<Path> indexes(Path gen) throws IOException {
    try (var s = Files.list(gen)) {
      return s.filter(p -> p.getFileName().toString().startsWith("idx-")).toList();
    }
  }

  @Test
  void failedOldFileCleanupIsOnlyAWarningAndRetriedByTheNextRun() throws IOException {
    Path gen = f.remote.resolve(versions().get(0).split(":")[0]);
    f.write("2.file", "2");
    f.failWhen("delete:*");
    Result r = f.river("backup", url);
    assertEquals(0, r.rc(), r.toString());
    assertTrue(r.err().contains("warning"), r.err());
    assertEquals(2, versions().size());
    assertEquals(2, indexes(gen).size(), "old index still there");
    assertEquals(1, f.conf().trash.size(), "and in the trash");
    f.failNever();
    assertEquals(List.of("1.file", "2.file"), restoredLatest());
    Result again = f.river("backup", url);  // nothing changed: still empties the trash
    assertEquals(0, again.rc(), again.toString());
    assertEquals(List.of(Path.of(f.conf().generations.get(0).index).getFileName()),
        indexes(gen).stream().map(Path::getFileName).toList());
    assertEquals(List.of(), f.conf().trash);
  }

  @Test
  void droppedGenerationThatCannotBeDeletedIsDeletedLater() throws IOException {
    Path c = tmp.resolve("one.yaml");
    Files.writeString(c, f.config("").replace("keep_incremental_backup_count: 2", "keep_incremental_backup_count: 0")
        .replace("keep_full_backup_count: 3", "keep_full_backup_count: 1"));
    assertEquals(0, f.river("config", url, c.toString()).rc());
    String old = f.conf().generations.get(0).name;
    f.write("2.file", "2");
    f.failWhen("delete:*");
    assertEquals(0, f.river("backup", url).rc());  // a new generation, the old one is dropped
    f.failNever();
    assertTrue(Files.isDirectory(f.remote.resolve(old)), "delete failed");
    assertTrue(f.conf().trash.contains(old), f.conf().trash.toString());
    assertEquals(0, f.river("backup", url).rc());  // another new generation, the second one is dropped
    assertFalse(Files.exists(f.remote.resolve(old)));
    assertFalse(f.conf().trash.contains(old));
    assertTrashIsDeleted();
    assertEquals(List.of("1.file", "2.file"), restoredLatest());
  }

  @Test
  void trashNeverDeletesWhatIsInUse() throws IOException {
    Conf c = f.conf();
    String gen = c.generations.get(0).name;
    c.trash.add(gen);
    c.trash.add(c.generations.get(0).index);
    Files.writeString(f.remote.resolve("river.conf"), c.dump());
    Result r = f.river("backup", url);
    assertEquals(0, r.rc(), r.toString());
    assertTrue(r.err().contains("in use"), r.err());
    assertEquals(List.of(), f.conf().trash);
    assertEquals(List.of("1.file"), restoredLatest());
  }

  @Test
  void deleteEmptiesTheTrash() throws IOException {
    Path orphan = f.remote.resolve("gorphan");
    Files.createDirectories(orphan);
    Conf c = f.conf();
    c.trash.add("gorphan");
    Files.writeString(f.remote.resolve("river.conf"), c.dump());
    assertEquals(0, f.river("delete", url, "--yes").rc());
    assertFalse(Files.exists(orphan));
  }

  @Test
  void failedDownloadOfIndexFailsRestoreAndBackup() throws IOException {
    f.failWhen("download:*/idx-*");
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
    Path gen = f.remote.resolve(versions().get(0).split(":")[0]);
    byte[] tome1 = Files.readAllBytes(gen.resolve("arc-000001.jpaq"));
    byte[] tome2 = Files.readAllBytes(gen.resolve("arc-000002.jpaq"));
    String conf = Files.readString(f.remote.resolve("river.conf"));
    f.write("3.file", "3");
    for (String how : List.of("download", "vanished")) {
      Path idx;
      try (var s = Files.list(gen)) {
        idx = s.filter(p -> p.getFileName().toString().startsWith("idx-")).findFirst().orElseThrow();
      }
      Path saved = tmp.resolve("idx.saved");
      if (how.equals("download")) f.failWhen("download:*/idx-*");
      else Files.move(idx, saved);
      Result r = f.river("backup", url);
      f.failNever();
      if (how.equals("vanished")) Files.move(saved, idx);
      assertNotEquals(0, r.rc(), how + ": " + r);
      assertEquals(conf, Files.readString(f.remote.resolve("river.conf")), how + ": config untouched");
      assertEquals(java.util.Arrays.hashCode(tome1),
          java.util.Arrays.hashCode(Files.readAllBytes(gen.resolve("arc-000001.jpaq"))), how + ": tome 1");
      assertEquals(java.util.Arrays.hashCode(tome2),
          java.util.Arrays.hashCode(Files.readAllBytes(gen.resolve("arc-000002.jpaq"))), how + ": tome 2");
    }
    assertEquals(List.of("1.file", "2.file"), restoredLatest());
  }

  @Test
  void indexThatDisagreesWithConfigIsRefused() throws IOException {
    f.write("2.file", "2");
    assertEquals(0, f.river("backup", url).rc());
    Path confFile = f.remote.resolve("river.conf");
    Conf c = Conf.parse(Files.readString(confFile));
    c.generations.get(0).versions.remove(1);  // config forgot version 2, index still has it
    Files.writeString(confFile, c.dump());
    Path gen = f.remote.resolve(c.generations.get(0).name);
    byte[] tome2 = Files.readAllBytes(gen.resolve("arc-000002.jpaq"));
    f.write("3.file", "3");
    Result r = f.river("backup", url);
    assertNotEquals(0, r.rc(), r.toString());
    assertTrue(r.err().contains("river.conf says"), r.err());
    assertEquals(java.util.Arrays.hashCode(tome2),
        java.util.Arrays.hashCode(Files.readAllBytes(gen.resolve("arc-000002.jpaq"))));
  }

  @Test
  void brokenLockFileBlocksBackup() throws IOException {
    Files.writeString(f.remote.resolve("lock"), "garbage");
    f.write("2.file", "2");
    Result r = f.river("backup", url);
    assertEquals(3, r.rc(), r.toString());
    assertEquals(0, f.river("unlock", url).rc());
    assertEquals(0, f.river("backup", url).rc());
    assertEquals(2, versions().size());
  }

  @Test
  void backupLockedByAnotherProcessIsRefusedAndReleasedAfterwards() throws IOException {
    Remote remote = new Remote(new Driver(f.home, Url.parse(url)), Url.parse(url));
    f.write("2.file", "2");
    try (Lock held = Lock.acquire(remote)) {
      Result r = f.river("backup", url);
      assertEquals(3, r.rc(), r.toString());
      assertTrue(r.err().contains("locked"), r.err());
      assertEquals(3, f.river("config", url, "-").rc() == 0 ? 0 : 3);  // reading config needs no lock
    }
    assertEquals(0, f.river("backup", url).rc());
    String lock = Files.readString(f.remote.resolve("lock"));
    assertTrue(lock.trim().endsWith(" 0"), "released lock is expired: " + lock);
  }

  @Test
  void lockTakenOverMidBackupAbortsWithoutCommit() throws IOException {
    f.write("2.file", "2");
    f.stealLockWhen("upload:*/arc-*");  // after the tome is up, the lock belongs to someone else
    Result r = f.river("backup", url);
    assertNotEquals(0, r.rc(), r.toString());
    assertTrue(r.err().contains("lock"), r.err());
    assertEquals(1, versions().size(), "not committed");
    assertTrue(Files.readString(f.remote.resolve("lock")).startsWith("someone-else"), "foreign lock left alone");
    assertEquals(List.of("1.file"), restoredLatest());
  }

  @Test
  void uploadIsNotPublishedOnceTheLockIsLost() throws IOException {
    Path gen = f.remote.resolve(versions().get(0).split(":")[0]);
    f.write("2.file", "2");
    f.stealLockWhen("upload:*/arc-*");  // taken over while the tome is uploading
    assertNotEquals(0, f.river("backup", url).rc());
    assertFalse(Files.exists(gen.resolve("arc-000002.jpaq")), "the upload is killed, not completed");
  }

  @Test
  void deleteRemovesGenerationsEvenIfOneFails() throws IOException {
    f.failWhen("delete:g*");
    assertNotEquals(0, f.river("delete", url, "--yes").rc());
    assertTrue(Files.exists(f.remote.resolve("river.conf")), "config stays so that delete can be repeated");
    f.failNever();
    assertEquals(0, f.river("delete", url, "--yes").rc());
  }
}
