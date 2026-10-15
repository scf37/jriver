package io.github.scf37.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.scf37.river.Remote.LockEntry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockTest {
  @TempDir Path tmp;
  Fixture f;
  Remote remote;
  final AtomicLong clock = new AtomicLong(1_000_000_000_000L);
  final Lock.Timing timing = new Lock.Timing(300, 1, 30, 60, 20);

  @BeforeEach
  void setUp() throws IOException {
    f = new Fixture(tmp);
    f.create("paths: ['/x']\n");
    remote = new Remote(new Driver(f.home, Url.parse(f.url)), Url.parse(f.url));
  }

  private Lock acquire() {
    return Lock.acquire(remote, timing, clock::get);
  }

  private void writeLock(String uuid, long expires) throws IOException {
    remote.writeLock(new LockEntry(uuid, expires));
  }

  @Test
  void acquireExcludesOthersUntilReleased() {
    Lock a = acquire();
    RiverException e = assertThrows(RiverException.class, this::acquire);
    assertEquals(RiverException.LOCKED, e.exitCode);
    a.close();
    acquire().close();
  }

  @Test
  void expiredLockIsTakenOnlyAfterTheSkewAllowance() throws IOException {
    long now = clock.get() / 1000;
    writeLock("old", now - 5);
    assertThrows(RiverException.class, this::acquire);
    writeLock("old", now - 31);
    acquire().close();
  }

  @Test
  void unreadableLockCountsAsHeld() throws IOException {
    Files.writeString(f.remote.resolve("lock"), "not a lock");
    assertThrows(RiverException.class, this::acquire);
    Files.delete(f.remote.resolve("lock"));
    assertThrows(RiverException.class, this::acquire);
  }

  @Test
  void releaseExpiresTheLockRegardlessOfSkew() throws IOException {
    acquire().close();
    assertEquals(0, remote.readLock().expires());
    acquire().close();
  }

  @Test
  void exactlyOneOfManyRacersWins() throws Exception {
    for (int round = 0; round < 5; round++) {
      writeLock("none", 0);
      AtomicInteger won = new AtomicInteger();
      Lock[] locks = new Lock[4];
      Thread[] ts = new Thread[4];
      for (int i = 0; i < ts.length; i++) {
        int n = i;
        ts[i] = new Thread(() -> {
          try {
            locks[n] = Lock.acquire(remote, new Lock.Timing(300, 60, 30, 60, 300), clock::get);
            won.incrementAndGet();
          } catch (RiverException expected) {
          }
        });
      }
      for (Thread t : ts) t.start();
      for (Thread t : ts) t.join();
      for (Lock l : locks) if (l != null) l.close();
      assertEquals(1, won.get(), "round " + round);
    }
  }

  @Test
  void heartbeatRenewsTheLease() throws Exception {
    try (Lock l = acquire()) {
      long first = remote.readLock().expires();
      clock.addAndGet(100_000);  // 100 s later the heartbeat extends the lease
      Thread.sleep(2500);
      assertTrue(remote.readLock().expires() >= first + 100, "renewed");
      l.beforeWrite();
    }
  }

  @Test
  void takeoverIsDetectedByHeartbeatAndByCheck() throws Exception {
    try (Lock l = acquire()) {
      writeLock("thief", clock.get() / 1000 + 300);
      assertThrows(IOException.class, l::check);
      assertThrows(IOException.class, l::beforeWrite);
    }
    assertTrue(Files.readString(f.remote.resolve("lock")).startsWith("thief"), "close leaves a foreign lock alone");
  }

  @Test
  void heartbeatNoticesTakeoverAndKillsUploads() throws Exception {
    try (Lock l = acquire()) {
      boolean[] aborted = {false};
      l.onLost(() -> aborted[0] = true);
      writeLock("thief", clock.get() / 1000 + 300);
      Thread.sleep(2500);
      assertTrue(aborted[0], "abort hook ran");
      assertThrows(IOException.class, l::beforeWrite);
    }
  }

  @Test
  void aFrozenProcessStopsWritingWhenItWakesUp() throws Exception {
    try (Lock l = acquire()) {
      l.beforeWrite();
      clock.addAndGet(300_000);  // slept through the whole TTL
      assertThrows(IOException.class, l::beforeWrite);
    }
  }

  @Test
  void storageWritesOnlyWhileTheLockIsOurs() throws Exception {
    Lock l = acquire();
    try {
      DriverStorage st = new DriverStorage(remote.driver, l);
      try (OutputStream o = st.write("a/ok.bin")) {
        o.write(new byte[] {1, 2, 3});
      }
      assertEquals(3, Files.size(f.remote.resolve("a/ok.bin")));

      OutputStream o = st.write("a/stale.bin");
      o.write(new byte[] {1});
      clock.addAndGet(400_000);  // frozen past the TTL
      assertThrows(IOException.class, () -> o.write(new byte[] {2}));
      assertThrows(IOException.class, o::close);
      assertFalse(Files.exists(f.remote.resolve("a/stale.bin")), "the upload never completed");

      assertThrows(IOException.class, () -> st.write("a/never.bin"), "not even opened");
      assertFalse(Files.exists(f.remote.resolve("a/never.bin")));
    } finally {
      l.close();
    }
  }

  @Test
  void storageDoesNotOpenAfterTakeover() throws Exception {
    try (Lock l = acquire()) {
      DriverStorage st = new DriverStorage(remote.driver, l);
      writeLock("thief", clock.get() / 1000 + 300);
      assertThrows(IOException.class, () -> st.write("a/x.bin"));
    }
  }

  @Test
  void localLockPreventsSecondStartOnTheSameUrl() {
    Url u = Url.parse("local:/some/where");
    try (LocalLock a = LocalLock.acquire(tmp.resolve("ll"), u)) {
      RiverException e = assertThrows(RiverException.class, () -> LocalLock.acquire(tmp.resolve("ll"), u));
      assertEquals(RiverException.LOCKED, e.exitCode);
      try (LocalLock other = LocalLock.acquire(tmp.resolve("ll"), Url.parse("local:/else"))) {
        assertFalse(other == null);
      }
    }
    LocalLock.acquire(tmp.resolve("ll"), u).close();
  }
}
