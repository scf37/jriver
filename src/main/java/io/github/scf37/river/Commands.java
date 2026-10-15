package io.github.scf37.river;

import io.github.scf37.jpaq.api.Command;
import io.github.scf37.jpaq.api.ErrorLine;
import io.github.scf37.jpaq.api.Format;
import io.github.scf37.jpaq.api.InfoLine;
import io.github.scf37.jpaq.api.Line;
import io.github.scf37.jpaq.api.Options;
import io.github.scf37.jpaq.cli.Zpaq;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** The river commands. Every method returns the exit code: 0 ok, 1 ok with warnings. */
final class Commands {
  private static final DateTimeFormatter GEN_NAME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);
  private static final String TOME_PATTERN = "arc-??????.jpaq";

  private final Path home;
  private final Map<String, String> env;
  private final PrintStream out;
  private final PrintStream err;
  private final boolean verbose;

  Commands(Path home, Map<String, String> env, PrintStream out, PrintStream err, boolean verbose) {
    this.home = home;
    this.env = env;
    this.out = out;
    this.err = err;
    this.verbose = verbose;
  }

  // ---------------------------------------------------------------- helpers

  private Remote remote(Url url) {
    return new Remote(new Driver(home, url), url);
  }

  private Path lockDir() {
    String d = env.get("river_lock_dir");
    if (d != null && !d.isEmpty()) return Path.of(d);
    String xdg = env.get("XDG_CACHE_HOME");
    if (xdg != null && !xdg.isEmpty()) return Path.of(xdg, "river");
    String h = env.get("HOME");
    return h != null ? Path.of(h, ".cache", "river") : Path.of(System.getProperty("java.io.tmpdir"), "river");
  }

  private String key() {
    String k = env.get("river_key");
    if (k != null && !k.isEmpty()) return k;
    String f = env.get("river_key_file");
    if (f != null && !f.isEmpty()) {
      try {
        String s = Files.readString(Path.of(f), StandardCharsets.UTF_8);
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
      } catch (IOException e) {
        throw new RiverException("cannot read river_key_file " + f + ": " + e.getMessage(), e);
      }
    }
    return "";
  }

  private void checkKey(boolean encrypted) {
    boolean has = !key().isEmpty();
    if (encrypted && !has)
      throw new RiverException("backup is encrypted: pass the key in river_key (or river_key_file) environment variable");
    if (!encrypted && has)
      throw new RiverException("river_key is set but the backup is not encrypted (encrypted: false in config)");
  }

  private Options options(Conf c) {
    Options o = new Options().method(c.method);
    if (!c.exclude.isEmpty()) o.not(c.exclude.toArray(String[]::new));
    if (!c.includeOnly.isEmpty()) o.only(c.includeOnly.toArray(String[]::new));
    return o;
  }

  private Options withKey(Options o, Conf c) {
    return c.encrypted ? o.key(key()) : o;
  }

  private static String now() {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
  }

  /** Runs a jpaq command, shows its output; returns its exit code (0, 1 = warnings). */
  private int zpaq(DriverStorage storage, Command cmd, Consumer<Line> extra) {
    Consumer<Line> sink = l -> {
      // problems are always shown; a date moved to keep versions in order is only information
      if (l instanceof ErrorLine && !(l instanceof ErrorLine.DateAdjusted)) err.print(l.text());
      else if (verbose) err.print(l.text());
      if (extra != null) extra.accept(l);
    };
    int rc = Zpaq.run(storage, Path.of("").toAbsolutePath(), cmd, sink);
    if (storage.poisoned() != null) throw new RiverException(storage.poisoned());
    if (rc >= 2) throw new RiverException("archiver failed (exit code " + rc + ")");
    return rc;
  }

  private static String readAll(String file, InputStream stdin) {
    try {
      if (file == null || file.equals("-")) return new String(stdin.readAllBytes(), StandardCharsets.UTF_8);
      Path p = Path.of(file);
      if (!Files.exists(p)) throw new RiverException("configuration file not found: " + file);
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RiverException("cannot read " + file + ": " + e.getMessage(), e);
    }
  }

  // ---------------------------------------------------------------- commands

  int init(String file) {
    String example;
    try (InputStream in = Commands.class.getResourceAsStream("/river.conf.example")) {
      example = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RiverException("cannot read the example config", e);
    }
    if (file == null || file.equals("-")) {
      out.print(example);
      return 0;
    }
    Path p = Path.of(file);
    if (Files.exists(p)) throw new RiverException("file " + file + " already exists, refusing to overwrite");
    try {
      Files.writeString(p, example, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RiverException("cannot write " + file + ": " + e.getMessage(), e);
    }
    return 0;
  }

  int create(Url url, String confFile, InputStream stdin) {
    Conf conf = Conf.parseConfig(readAll(confFile, stdin), "config");
    if (conf.paths.isEmpty()) throw new RiverException("config: paths must not be empty");
    checkKey(conf.encrypted);
    Remote remote = remote(url);
    if (remote.confExists()) throw new RiverException("backup already exists at " + url);
    try {
      remote.writeLock(new Remote.LockEntry("none", 0));
      remote.writeConf(conf);
    } catch (IOException e) {
      throw new RiverException("cannot create backup at " + url + ": " + e.getMessage(), e);
    }
    return 0;
  }

  int config(Url url, String confFile, InputStream stdin) {
    Remote remote = remote(url);
    if (confFile == null) {
      out.print(remote.readConf().dumpConfig());
      return 0;
    }
    Conf cfg = Conf.parseConfig(readAll(confFile, stdin), "config");
    if (cfg.paths.isEmpty()) throw new RiverException("config: paths must not be empty");
    checkKey(cfg.encrypted);
    try (LocalLock ll = LocalLock.acquire(lockDir(), url); Lock lock = Lock.acquire(remote)) {
      Conf cur = remote.readConf();
      if (cur.encrypted != cfg.encrypted && !cur.generations.isEmpty())
        throw new RiverException("cannot change 'encrypted' of a backup that already has versions");
      lock.check();
      remote.writeConf(cur.withConfigFrom(cfg));
    } catch (IOException e) {
      throw new RiverException("cannot update config: " + e.getMessage(), e);
    }
    return 0;
  }

  int unlock(Url url) {
    try {
      remote(url).writeLock(new Remote.LockEntry("unlocked", 0));
    } catch (IOException e) {
      throw new RiverException("cannot unlock: " + e.getMessage(), e);
    }
    return 0;
  }

  int backup(Url url) {
    Driver driver = new Driver(home, url);
    Remote remote = new Remote(driver, url);
    try (LocalLock ll = LocalLock.acquire(lockDir(), url); Lock lock = Lock.acquire(remote)) {
      Conf conf = remote.readConf();
      String fingerprint = conf.stateFingerprint();
      checkKey(conf.encrypted);
      if (conf.paths.isEmpty()) throw new RiverException("no paths to back up in config");

      // keep_incremental_backup_count incrementals follow the first, full, version of a generation
      Conf.Generation last = conf.lastGeneration();
      boolean fresh = last == null || last.versions.size() > conf.keepIncrementalBackupCount;
      Conf.Generation gen = fresh ? new Conf.Generation("g" + GEN_NAME.format(Instant.now())) : last;
      String indexOut = gen.name + "/idx-" + System.currentTimeMillis() + "-"
          + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt(65536));
      indexOut += ".jpaq";

      Command.Add add = new Command.Add(gen.name + "/" + TOME_PATTERN, conf.paths,
          withKey(options(conf), conf), Format.SEQUENTIAL, gen.index, indexOut);
      boolean[] indexWritten = {false};
      DriverStorage storage = new DriverStorage(driver, lock);
      int rc;
      try {
        rc = zpaq(storage, add, l -> {
          // the index must be the one river.conf says: otherwise jpaq would number the new tome wrongly
          if (l instanceof InfoLine.ArchiveRead r && gen.index != null && r.versions() != gen.versions.size())
            storage.poison("index " + gen.index + " has " + r.versions() + " versions but river.conf says "
                + gen.versions.size() + "; refusing to write");
          // an update with changes writes a tome and the index; an empty one writes nothing
          if (l instanceof InfoLine.AddResult r && r.addedBytes() > 0) indexWritten[0] = true;
        });
      } catch (RuntimeException e) {
        if (fresh) quietDelete(driver, gen.name);
        throw e;
      }
      if (!indexWritten[0]) {
        if (verbose) err.println("nothing changed, no new version");
        if (fresh) quietDelete(driver, gen.name);
        return rc;
      }

      // commit: only if we still own the lock and nobody changed the state under us
      lock.check();
      Conf cur = remote.readConf();
      if (!cur.stateFingerprint().equals(fingerprint))
        throw new RiverException("river.conf changed while the backup was running, not committing");
      String oldIndex = gen.index;
      gen.index = indexOut;
      gen.versions.add(new Conf.Version(gen.versions.size() + 1, now()));
      if (fresh) conf.generations.add(gen);
      List<String> dropped = new ArrayList<>();
      while (conf.generations.size() > conf.keepFullBackupCount) dropped.add(conf.generations.remove(0).name);
      remote.writeConf(conf);

      // cleanup, best effort: leftovers are removed with their generation
      try {
        lock.check();
        if (oldIndex != null) quietDelete(driver, oldIndex);
        for (String d : dropped) quietDelete(driver, d);
      } catch (IOException e) {
        err.println("warning: lock lost after commit, not deleting old files: " + e.getMessage());
      }
      return rc;
    } catch (IOException e) {
      throw new RiverException("backup failed: " + e.getMessage(), e);
    }
  }

  private void quietDelete(Driver driver, String rel) {
    try {
      driver.delete(rel);
    } catch (IOException e) {
      err.println("warning: " + e.getMessage());
    }
  }

  int list(Url url, String filesVersion) {
    Remote remote = remote(url);
    Conf conf = remote.readConf();
    if (filesVersion == null) {
      for (Conf.Generation g : conf.generations)
        for (Conf.Version v : g.versions) out.println(g.name + ":" + v.n() + "\t" + v.time());
      return 0;
    }
    checkKey(conf.encrypted);
    Selected s = select(conf, filesVersion);
    Options o = withKey(new Options().until(Integer.toString(s.version.n())), conf);
    List<String> names = new ArrayList<>();
    zpaq(new DriverStorage(remote.driver, Guard.NONE),
        new Command.ListArchive(s.gen.name + "/" + TOME_PATTERN, List.of(), o, s.gen.index), l -> {
          if (l instanceof InfoLine.ListEntry e && !e.name().endsWith("/")) names.add(e.name());
        });
    names.forEach(out::println);
    return 0;
  }

  int restore(Url url, String version, String to, List<String> files, boolean verify) {
    Remote remote = remote(url);
    Conf conf = remote.readConf();
    checkKey(conf.encrypted);
    Selected s = select(conf, version);
    Options o = withKey(new Options().until(Integer.toString(s.version.n())).force(true), conf);
    return zpaq(new DriverStorage(remote.driver, Guard.NONE),
        new Command.Extract(s.gen.name + "/" + TOME_PATTERN, files,
            to == null || verify ? List.of() : targets(to, files), o, s.gen.index, verify), null);
  }

  /**
   * Where to extract to: without file patterns the whole tree goes under {@code dir} (the archive
   * keeps absolute names); with patterns each match goes to {@code dir/<its last name component>}.
   */
  static List<String> targets(String dir, List<String> files) {
    if (files.isEmpty()) return List.of(dir);
    List<String> r = new ArrayList<>();
    for (String f : files) {
      String n = f.endsWith("/") ? f.substring(0, f.length() - 1) : f;
      n = n.substring(n.lastIndexOf('/') + 1);
      if (n.isEmpty() || n.contains("*") || n.contains("?"))
        throw new RiverException("--to with --files needs plain file or directory names, not '" + f + "'");
      r.add(dir.endsWith("/") ? dir + n : dir + "/" + n);
    }
    return r;
  }

  int delete(Url url) {
    Driver driver = new Driver(home, url);
    Remote remote = new Remote(driver, url);
    try (LocalLock ll = LocalLock.acquire(lockDir(), url); Lock lock = Lock.acquire(remote)) {
      Conf conf = remote.readConf();
      for (Conf.Generation g : conf.generations) {
        lock.check();
        driver.delete(g.name);
      }
      lock.check();
      driver.delete(Remote.CONF);
      // the lock file goes last; closing the lock then finds nothing and says so
      driver.delete(Remote.LOCK);
      lock.abandon();
    } catch (IOException e) {
      throw new RiverException("delete failed: " + e.getMessage(), e);
    }
    return 0;
  }

  // ---------------------------------------------------------------- versions

  record Selected(Conf.Generation gen, Conf.Version version) {}

  /** {@code <generation>:<n>} or {@code latest} (or empty). */
  static Selected select(Conf conf, String v) {
    if (v == null || v.isEmpty() || v.equals("latest")) {
      for (int i = conf.generations.size() - 1; i >= 0; i--) {
        Conf.Generation g = conf.generations.get(i);
        if (!g.versions.isEmpty()) return new Selected(g, g.versions.get(g.versions.size() - 1));
      }
      throw new RiverException("backup has no versions yet");
    }
    int i = v.lastIndexOf(':');
    if (i <= 0) throw new RiverException("incorrect version '" + v + "', expected <generation>:<n> or latest");
    Conf.Generation g = conf.generation(v.substring(0, i));
    if (g == null) throw new RiverException("no such generation: " + v.substring(0, i) + " (see river list)");
    int n;
    try {
      n = Integer.parseInt(v.substring(i + 1));
    } catch (NumberFormatException e) {
      throw new RiverException("incorrect version '" + v + "'");
    }
    for (Conf.Version ver : g.versions) if (ver.n() == n) return new Selected(g, ver);
    throw new RiverException("no such version: " + v + " (see river list)");
  }
}
