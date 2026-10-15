package io.github.scf37.river;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Per-machine lock on a backup url: prevents starting the same backup twice on this host. */
final class LocalLock implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  private LocalLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  static LocalLock acquire(Path dir, Url url) {
    try {
      Files.createDirectories(dir);
      byte[] h = MessageDigest.getInstance("SHA-256").digest(url.toString().getBytes(StandardCharsets.UTF_8));
      Path file = dir.resolve("river-" + HexFormat.of().formatHex(h, 0, 12) + ".lock");
      FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      FileLock l;
      try {
        l = ch.tryLock();
      } catch (OverlappingFileLockException e) {
        l = null;
      }
      if (l == null) {
        ch.close();
        throw new RiverException("this backup is already running on this machine", RiverException.LOCKED);
      }
      return new LocalLock(ch, l);
    } catch (IOException | java.security.NoSuchAlgorithmException e) {
      throw new RiverException("cannot take local lock in " + dir + ": " + e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    try {
      lock.release();
      channel.close();
    } catch (IOException ignored) {
    }
  }
}
