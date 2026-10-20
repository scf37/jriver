package io.github.scf37.river;

import io.github.scf37.jpaq.Storage;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * jpaq {@link Storage} for one full backup on top of a {@link Driver}: everything is streamed through the driver
 * scripts. jpaq works with logical names, the files at the backup root have names that carry the revision and a
 * random part (see {@link Names}); the config of a full backup says which is which:
 *
 * <ul>
 *   <li>{@code arc-00000k.jpaq} is the k-th of {@link Conf.FullBackup#tomes}
 *   <li>{@link #INDEX} is {@link Conf.FullBackup#index}
 *   <li>a tome written by this run gets a new name for the revision being committed, so does {@link #INDEX_OUT}
 * </ul>
 *
 * Nothing that was committed is ever overwritten or deleted by this class.
 */
final class DriverStorage implements Storage {
  /** The archive name to give to jpaq. */
  static final String ARCHIVE = "arc-??????.jpaq";
  /** Logical name of the index to read. */
  static final String INDEX = "index";
  /** Logical name of the index to write. */
  static final String INDEX_OUT = "index.new";

  private static final Pattern TOME = Pattern.compile("arc-(\\d{6})\\.jpaq");

  private final Driver driver;
  private final Conf.FullBackup gen;
  private final int revision;
  private final Map<String, String> written = new HashMap<>();  // logical -> physical name of what this run wrote
  private volatile String poisoned;

  /** Storage for the full backup gen; new files are named for revision. */
  DriverStorage(Driver driver, Conf.FullBackup gen, int revision) {
    this.driver = driver;
    this.gen = gen;
    this.revision = revision;
  }

  /** Storage that only reads. */
  DriverStorage(Driver driver, Conf.FullBackup gen) {
    this(driver, gen, 0);
  }

  /** The tome written by this run, null if none. */
  String newTome() {
    return written.entrySet().stream().filter(e -> TOME.matcher(e.getKey()).matches()).map(Map.Entry::getValue)
        .findFirst().orElse(null);
  }

  /** The index written by this run, null if none. */
  String newIndex() {
    return written.get(INDEX_OUT);
  }

  /**
   * Makes every further write fail and the whole command fail. jpaq goes on with an empty archive
   * when the index it was told to read cannot be read, and would then write a tome under a number that is taken;
   * a failed download is therefore fatal, whatever jpaq makes of it.
   */
  void poison(String why) {
    if (poisoned == null) poisoned = why;
  }

  /** Why this storage must not be trusted any more, null if all is well. */
  String poisoned() {
    return poisoned;
  }

  private String readName(String logical) throws IOException {
    if (logical.equals(INDEX)) {
      if (gen.index == null) throw new IOException("the full backup has no index");
      return gen.index;
    }
    Matcher m = TOME.matcher(logical);
    if (m.matches()) {
      int k = Integer.parseInt(m.group(1));
      if (k >= 1 && k <= gen.tomes.size()) return gen.tomes.get(k - 1);
      String mine = written.get(logical);
      if (mine != null) return mine;
    }
    throw new IOException("no such file in the archive: " + logical);
  }

  @Override
  public InputStream read(String logical) throws IOException {
    Process p;
    String name;
    try {
      name = readName(logical);
      p = driver.start("download", name, false);
      p.getOutputStream().close();
    } catch (IOException e) {
      poison("download " + logical + " failed: " + e.getMessage());
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
  public OutputStream write(String logical) throws IOException {
    if (poisoned != null) throw new IOException("not writing " + logical + ": " + poisoned);
    String name;
    Matcher m = TOME.matcher(logical);
    if (m.matches()) {
      // the tome number comes from the index; it must continue the tomes of the config
      if (Integer.parseInt(m.group(1)) != gen.tomes.size() + 1 || newTome() != null)
        throw new IOException("refusing to write " + logical + ": the full backup has " + gen.tomes.size() + " tomes");
      name = Names.tome(gen.name, revision);
    } else if (logical.equals(INDEX_OUT)) {
      name = Names.index(gen.name, revision);
    } else {
      throw new IOException("refusing to write " + logical);
    }
    written.put(logical, name);
    Process p = driver.start("upload", name, true);
    p.getInputStream().close();
    OutputStream out = p.getOutputStream();
    return new OutputStream() {
      private boolean closed;

      @Override
      public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
      }

      @Override
      public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
          out.close();
        } finally {
          Driver.check(p, "upload " + name);
        }
      }
    };
  }

  /** Only what this run wrote can be deleted (a partial tome after a failure). */
  @Override
  public void delete(String logical) throws IOException {
    String name = written.remove(logical);
    if (name != null) driver.delete(name);
  }
}
