package io.github.scf37.river;

import io.github.scf37.river.Remote.LockEntry;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * Remote lease lock: file {@code lock} holds {@code <uuid> <expiry>}. Taken by writing own uuid
 * with expiry now+TTL into an expired lock, confirmed by re-reading it after a short delay (the
 * last writer wins, the others see a foreign uuid), renewed every minute, released by setting the
 * expiry to 0 (in the past, whatever the clocks).
 *
 * <p>As a {@link Guard} it makes sure nothing is written when the lease cannot be trusted any
 * more: a heartbeat that finds a foreign uuid, or cannot renew in time, fails the lock and aborts
 * running uploads; every write also compares the wall clock with the validity deadline, so a
 * process that was frozen for longer than the TTL stops at its first write after waking up. Opening
 * and publishing a blob re-read the lock file.
 */
final class Lock implements Guard, AutoCloseable {
  /** Timing, in seconds (milliseconds for the confirm delay); system properties exist for tests. */
  record Timing(long ttl, long heartbeat, long skew, long margin, long confirmMs) {
    static Timing fromProperties() {
      long ttl = Long.getLong("river.lock.ttl", 300);
      return new Timing(
          ttl,
          Long.getLong("river.lock.heartbeat", 60),
          Long.getLong("river.lock.skew", 30),
          Long.getLong("river.lock.margin", Math.min(60, ttl / 5)),
          Long.getLong("river.lock.confirmMs", 3000));
    }
  }

  private final Remote remote;
  private final Timing t;
  private final LongSupplier clockMs;
  private final String uuid = UUID.randomUUID().toString();
  private final List<Runnable> abortHooks = new CopyOnWriteArrayList<>();
  private volatile long deadlineMs;  // usable until this wall clock time
  private volatile String lost;
  private volatile boolean released;
  private Thread heartbeat;

  private Lock(Remote remote, Timing t, LongSupplier clockMs) {
    this.remote = remote;
    this.t = t;
    this.clockMs = clockMs;
  }

  static Lock acquire(Remote remote) {
    return acquire(remote, Timing.fromProperties(), System::currentTimeMillis);
  }

  static Lock acquire(Remote remote, Timing t, LongSupplier clockMs) {
    Lock l = new Lock(remote, t, clockMs);
    l.take();
    return l;
  }

  private long nowSec() {
    return clockMs.getAsLong() / 1000;
  }

  private void take() {
    try {
      LockEntry cur = remote.readLock();
      if (nowSec() <= cur.expires() + t.skew())
        throw new RiverException("backup is locked by another process until "
            + java.time.Instant.ofEpochSecond(cur.expires()) + " (river unlock <url> to force)", RiverException.LOCKED);
      LockEntry mine = new LockEntry(uuid, nowSec() + t.ttl());
      remote.writeLock(mine);
      Thread.sleep(t.confirmMs());
      LockEntry again = remote.readLock();
      if (!again.uuid().equals(uuid))
        throw new RiverException("lost the race for the backup lock to another process", RiverException.LOCKED);
      deadlineMs = (mine.expires() - t.margin()) * 1000;
    } catch (IOException e) {
      throw new RiverException("cannot take the lock (" + e.getMessage() + "); is the backup created? "
          + "unreadable lock is treated as held", RiverException.LOCKED);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RiverException("interrupted");
    }
    heartbeat = new Thread(this::beat, "river-lock-heartbeat");
    heartbeat.setDaemon(true);
    heartbeat.start();
  }

  private void beat() {
    while (!released && lost == null) {
      try {
        Thread.sleep(t.heartbeat() * 1000);
      } catch (InterruptedException e) {
        return;
      }
      if (released) return;
      try {
        LockEntry cur = remote.readLock();
        if (!cur.uuid().equals(uuid)) {
          fail("the lock was taken over by another process");
          return;
        }
        LockEntry next = new LockEntry(uuid, nowSec() + t.ttl());
        remote.writeLock(next);
        deadlineMs = (next.expires() - t.margin()) * 1000;
      } catch (IOException e) {
        if (clockMs.getAsLong() > deadlineMs) {
          fail("cannot renew the lock: " + e.getMessage());
          return;
        }
      }
    }
  }

  private void fail(String why) {
    lost = why;
    for (Runnable r : abortHooks) r.run();
  }

  @Override
  public Runnable onLost(Runnable abort) {
    abortHooks.add(abort);
    if (lost != null) abort.run();
    return () -> abortHooks.remove(abort);
  }

  @Override
  public void beforeWrite() throws IOException {
    if (lost != null) throw new IOException("lock lost: " + lost);
    if (clockMs.getAsLong() > deadlineMs) throw new IOException("lock lease expired, not trusting it any more");
  }

  /** Full check against the remote lock file. */
  @Override
  public void verify() throws IOException {
    check();
  }

  /** Throws unless the remote lock is still ours and valid for a while. */
  void check() throws IOException {
    beforeWrite();
    LockEntry cur = remote.readLock();
    if (!cur.uuid().equals(uuid)) {
      fail("the lock was taken over by another process");
      throw new IOException("lock lost: " + lost);
    }
    if (cur.expires() * 1000 - t.margin() * 1000 < clockMs.getAsLong())
      throw new IOException("lock lease expired, not trusting it any more");
  }

  /** The lock file is gone (backup deleted): nothing to release. */
  void abandon() {
    lost = "abandoned";
  }

  @Override
  public void close() {
    released = true;
    if (heartbeat != null) heartbeat.interrupt();
    if (lost != null) return;  // not ours any more, leave it alone
    try {
      LockEntry cur = remote.readLock();
      if (cur.uuid().equals(uuid)) remote.writeLock(new LockEntry(uuid, 0));  // 0, not now: skew allowance must not delay the next run
    } catch (IOException e) {
      System.err.println("warning: cannot release the lock, it expires on its own: " + e.getMessage());
    }
  }
}
