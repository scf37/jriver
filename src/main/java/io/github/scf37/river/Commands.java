package io.github.scf37.river;

import io.github.scf37.jpaq.ArchiveContents;
import io.github.scf37.jpaq.Event;
import io.github.scf37.jpaq.ZpaqArchive;
import io.github.scf37.jpaq.ZpaqArchive.Format;
import io.github.scf37.jpaq.ZpaqArchive.Option;
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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** The river commands. Every method returns the exit code: 0 ok, 1 ok with warnings. */
final class Commands {
  private static final DateTimeFormatter FULL_NAME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

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
    return new Remote(new Driver(home, url), url, keyOrNull());
  }

  private String keyOrNull() {
    String k = key();
    return k.isEmpty() ? null : k;
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

  /** True if a zpaq pattern matches the name: equal, or a directory the name is in; * and ? are wildcards. */
  static boolean ispath(String pattern, String name) {
    return ispath(pattern, 0, name, 0);
  }

  private static boolean ispath(String a, int ai, String b, int bi) {
    for (; ai < a.length(); ++ai, ++bi) {
      char ca = a.charAt(ai);
      if (ca == '*') {
        while (true) {
          if (ispath(a, ai + 1, b, bi)) return true;
          if (bi >= b.length()) return false;
          ++bi;
        }
      }
      if (bi >= b.length()) return false;
      char cb = b.charAt(bi);
      if (ca == '/' && cb == '/' && ai == a.length() - 1) return true;
      if (ca != '?' && ca != cb) return false;
    }
    return bi >= b.length() || b.charAt(bi) == '/';
  }

  private static boolean anyPath(List<String> patterns, String name) {
    for (String p : patterns) if (ispath(p, name)) return true;
    return false;
  }

  private static String now() {
    return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
  }

  /** An operation on the archive. */
  private interface Op<T> {
    T run(ZpaqArchive archive) throws IOException;
  }

  /** Runs an operation on an archive in storage, shows what it reports. */
  private <T> T zpaq(DriverStorage storage, String archive, Conf c, Consumer<Event> extra, Op<T> op) {
    Consumer<Event> listener = e -> {
      // problems are always shown; a date moved to keep versions in order is only information
      String text = Events.text(e);
      if (text != null && (verbose || e instanceof Event.Problem && !(e instanceof Event.DateAdjusted)))
        err.println(text);
      if (extra != null) extra.accept(e);
    };
    ZpaqArchive a = ZpaqArchive.of(storage, archive, Format.SEQUENTIAL,
        Option.key(c.encrypted ? key() : null), Option.listener(listener));
    T r;
    try {
      r = op.run(a);
    } catch (IOException | RuntimeException e) {
      if (storage.poisoned() != null) throw new RiverException(storage.poisoned(), e);
      throw new RiverException("archiver failed: " + e.getMessage(), e);
    }
    if (storage.poisoned() != null) throw new RiverException(storage.poisoned());
    return r;
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
    try {
      if (remote.exists()) throw new RiverException("backup already exists at " + url);
      conf.revision = 1;
      remote.commit(conf);
    } catch (IOException e) {
      throw new RiverException("cannot create backup at " + url + ": " + e.getMessage(), e);
    }
    return 0;
  }

  int config(Url url, String confFile, InputStream stdin) {
    Remote remote = remote(url);
    if (confFile == null) {
      out.print(remote.latest().conf().dumpConfig());
      return 0;
    }
    Conf cfg = Conf.parseConfig(readAll(confFile, stdin), "config");
    if (cfg.paths.isEmpty()) throw new RiverException("config: paths must not be empty");
    checkKey(cfg.encrypted);
    try (LocalLock ll = LocalLock.acquire(lockDir(), url)) {
      Remote.Snapshot snap = remote.latest();
      Conf cur = snap.conf();
      if (cur.encrypted != cfg.encrypted && !cur.fullBackups.isEmpty())
        throw new RiverException("cannot change 'encrypted' of a backup that already has versions");
      Conf next = cur.withConfigFrom(cfg);
      next.revision = snap.revision() + 1;
      remote.commit(next);
      remote.cleanup(next.revision, err::println);
    } catch (IOException e) {
      throw new RiverException("cannot update config: " + e.getMessage(), e);
    }
    return 0;
  }

  int backup(Url url) {
    Driver driver = new Driver(home, url);
    Remote remote = new Remote(driver, url, keyOrNull());
    try (LocalLock ll = LocalLock.acquire(lockDir(), url)) {
      Remote.Snapshot snap = remote.latest();
      Conf conf = Conf.parse(snap.conf().dump());  // a copy: the snapshot's own is what its revision committed
      checkKey(conf.encrypted);
      if (conf.paths.isEmpty()) throw new RiverException("no paths to back up in config");
      int revision = snap.revision() + 1;

      // keep_incremental_backup_count incrementals follow the first version of a full backup
      Conf.FullBackup last = conf.lastFullBackup();
      boolean fresh = last == null || last.versions.size() > conf.keepIncrementalBackupCount;
      Conf.FullBackup gen = fresh ? new Conf.FullBackup(FULL_NAME.format(Instant.now())) : last;

      DriverStorage storage = new DriverStorage(driver, gen, revision);
      ZpaqArchive.AddResult added = zpaq(storage, DriverStorage.ARCHIVE, conf, e -> {
        // the index must be the one the config says: otherwise jpaq would number the new tome wrongly
        if (e instanceof Event.ArchiveRead r && gen.index != null && r.versions() != gen.versions.size())
          storage.poison("index " + gen.index + " has " + r.versions() + " versions but the config says "
              + gen.versions.size() + "; refusing to write");
      }, a -> a.add(b -> b.files(conf.paths)
          .prune(n -> anyPath(conf.exclude, n))
          .accept(n -> (conf.includeOnly.isEmpty() || anyPath(conf.includeOnly, n)) && !anyPath(conf.exclude, n))
          .method(conf.method)
          .index(gen.index == null ? null : DriverStorage.INDEX)
          .indexOut(DriverStorage.INDEX_OUT)));
      int rc = added.unreadable() > 0 ? 1 : 0;
      // an update with changes writes a tome and the index; an empty one writes nothing
      if (added.addedBytes() == 0) {
        if (verbose) err.println("nothing changed, no new version");
        return rc;
      }
      String tome = storage.newTome();
      String index = storage.newIndex();
      if (tome == null || index == null) throw new RiverException("archiver wrote no tome or index");

      // commit: the sealed config of the next revision refers to the new files, which are all stored by now
      gen.index = index;
      gen.tomes.add(tome);
      gen.versions.add(new Conf.Version(gen.versions.size() + 1, now()));
      if (fresh) conf.fullBackups.add(gen);
      // beyond keep_full_backup_count the oldest full backup is simply not referenced any more; cleanup removes it
      while (conf.fullBackups.size() > conf.keepFullBackupCount) conf.fullBackups.remove(0);
      conf.revision = revision;
      remote.commit(conf);
      remote.cleanup(revision, err::println);
      return rc;
    } catch (IOException e) {
      throw new RiverException("backup failed: " + e.getMessage(), e);
    }
  }

  /**
   * Runs a read-only operation on the latest backup state. If it fails and a newer revision has been committed
   * meanwhile (cleanup may have removed files of the one we used), it is run again on that.
   */
  private <T> T readLatest(Url url, Function<Remote.Snapshot, T> f) {
    Remote remote = remote(url);
    Remote.Snapshot snap = remote.latest();
    try {
      return f.apply(snap);
    } catch (RiverException e) {
      Remote.Snapshot again;
      try {
        again = remote.latestOrNull();
      } catch (IOException | RiverException x) {
        throw e;
      }
      if (again == null || again.revision() <= snap.revision()) throw e;
      return f.apply(again);
    }
  }

  int list(Url url, String filesVersion) {
    return readLatest(url, snap -> {
      Conf conf = snap.conf();
      if (filesVersion == null) {
        for (Conf.FullBackup g : conf.fullBackups)
          for (Conf.Version v : g.versions) out.println(g.name + ":" + v.n() + "\t" + v.time());
        return 0;
      }
      checkKey(conf.encrypted);
      Selected s = select(conf, filesVersion);
      ArchiveContents contents = zpaq(new DriverStorage(remote(url).driver, s.gen), DriverStorage.ARCHIVE, conf, null,
          a -> a.read(r -> r.index(DriverStorage.INDEX).version(s.version.n())));
      for (ArchiveContents.Entry e : contents.entries()) if (!e.isDirectory()) out.println(e.name());
      return 0;
    });
  }

  int restore(Url url, String version, String to, List<String> files, boolean verify) {
    return readLatest(url, snap -> {
      Conf conf = snap.conf();
      checkKey(conf.encrypted);
      Selected s = select(conf, version);
      List<String> targets = to == null || verify ? List.of() : targets(to, files);
      ZpaqArchive.ExtractResult r = zpaq(new DriverStorage(remote(url).driver, s.gen), DriverStorage.ARCHIVE, conf, null,
          a -> a.extract(x -> {
            x.index(DriverStorage.INDEX).version(s.version.n()).overwrite(true).test(verify);
            if (!files.isEmpty()) x.select(n -> anyPath(files, n));
            if (files.isEmpty() && !targets.isEmpty()) x.under(targets.get(0));
            else if (!targets.isEmpty()) x.output(n -> rename(n, files, targets));
          }));
      return r.ok() ? 0 : 1;
    });
  }

  /** The name with the first of {@code files} that is a prefix of it replaced by its target. */
  private static String rename(String name, List<String> files, List<String> targets) {
    for (int i = 0; i < files.size(); i++)
      if (name.startsWith(files.get(i))) return targets.get(i) + name.substring(files.get(i).length());
    return name;
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
    Remote remote = remote(url);
    try (LocalLock ll = LocalLock.acquire(lockDir(), url)) {
      if (!remote.exists()) throw new RiverException("no backup at " + url);
      remote.deleteAll(n -> {});
    } catch (IOException e) {
      throw new RiverException("delete failed: " + e.getMessage(), e);
    }
    return 0;
  }

  // ---------------------------------------------------------------- versions

  record Selected(Conf.FullBackup gen, Conf.Version version) {}

  /** {@code <full backup>:<n>} or {@code latest} (or empty). */
  static Selected select(Conf conf, String v) {
    if (v == null || v.isEmpty() || v.equals("latest")) {
      for (int i = conf.fullBackups.size() - 1; i >= 0; i--) {
        Conf.FullBackup g = conf.fullBackups.get(i);
        if (!g.versions.isEmpty()) return new Selected(g, g.versions.get(g.versions.size() - 1));
      }
      throw new RiverException("backup has no versions yet");
    }
    int i = v.lastIndexOf(':');
    if (i <= 0) throw new RiverException("incorrect version '" + v + "', expected <full backup>:<n> or latest");
    Conf.FullBackup g = conf.fullBackup(v.substring(0, i));
    if (g == null) throw new RiverException("no such full backup: " + v.substring(0, i) + " (see river list)");
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
