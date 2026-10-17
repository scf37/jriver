package io.github.scf37.river;

import io.github.scf37.jpaq.Storage;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * jpaq {@link Storage} on top of a {@link Driver}: everything is streamed through the driver
 * scripts. Writes are guarded: no blob is opened and no byte is sent unless the {@link Guard}
 * (the lock) says we still hold it; a lost guard kills the upload process so that it cannot finish.
 */
final class DriverStorage implements Storage {
  private final Driver driver;
  private final Guard guard;
  private volatile String poisoned;

  DriverStorage(Driver driver, Guard guard) {
    this.driver = driver;
    this.guard = guard;
  }

  /**
   * Makes every further write fail and the whole command fail. jpaq goes on with an empty archive
   * when the index it was told to read cannot be read, and would then overwrite committed tomes;
   * a failed download is therefore fatal, whatever jpaq makes of it.
   */
  void poison(String why) {
    if (poisoned == null) poisoned = why;
  }

  /** Why this storage must not be trusted any more, null if all is well. */
  String poisoned() {
    return poisoned;
  }

  @Override
  public InputStream read(String name) throws IOException {
    Process p;
    try {
      p = driver.start("download", name, false);
      p.getOutputStream().close();
    } catch (IOException e) {
      poison("download " + name + " failed: " + e.getMessage());
      throw e;
    }
    return new FilterInputStream(p.getInputStream()) {
      private boolean closed;
      private boolean eof;

      @Override
      public int read() throws IOException {
        int c = super.read();
        if (c < 0) eof = true;
        return c;
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n < 0) eof = true;
        return n;
      }

      @Override
      public void close() throws IOException {
        if (closed) return;
        closed = true;
        super.close();
        if (eof) {
          try {
            Driver.check(p, "download " + name);  // read to the end: exit code says if it all arrived
          } catch (IOException e) {
            poison(e.getMessage());
            throw e;
          }
        } else {
          p.destroy();
        }
      }
    };
  }

  @Override
  public OutputStream write(String name) throws IOException {
    if (poisoned != null) throw new IOException("not writing " + name + ": " + poisoned);
    guard.beforeOpen();
    Process p = driver.start("upload", name, true);
    p.getInputStream().close();
    OutputStream out = p.getOutputStream();
    return new OutputStream() {
      private boolean closed;
      private volatile boolean aborted;
      private final Runnable unregister = guard.onLost(this::abort);

      private void abort() {
        aborted = true;
        p.destroyForcibly();
      }

      private void check() throws IOException {
        if (aborted) throw new IOException("upload " + name + " aborted");
        try {
          guard.beforeWrite();
        } catch (IOException e) {
          abort();
          throw e;
        }
      }

      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        check();
        out.write(b, off, len);
      }

      @Override
      public void close() throws IOException {
        if (closed) return;
        closed = true;
        unregister.run();
        if (aborted) {
          p.destroyForcibly();
          throw new IOException("upload " + name + " aborted");
        }
        try {
          out.close();
        } finally {
          Driver.check(p, "upload " + name);
        }
      }
    };
  }

  @Override
  public void delete(String name) throws IOException {
    driver.delete(name);
  }
}
