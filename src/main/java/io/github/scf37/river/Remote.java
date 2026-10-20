package io.github.scf37.river;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The backup root: config files that commit revisions, and the tomes and indexes they refer to.
 *
 * <p>Tomes and indexes are in a folder per full backup. Storage needs no atomic operations. A revision is committed by writing a sealed config
 * {@code config.<revision>.<random>.yaml} after everything it refers to was stored completely. See
 * docs/remote-protocol.md.
 */
final class Remote {
  /** A usable config and the revision it commits. */
  record Snapshot(Conf conf, int revision, String name) {}

  /** A config file that is complete (ends with a seal line), valid or not. */
  private record Candidate(String name, ConfigFile.Sealed sealed) {}

  /** The well-formed configs of one revision. */
  private record Level(int revision, List<Candidate> configs) {}

  final Driver driver;
  final Url url;
  private final ConfigFile seal;
  private final Map<String, Conf> verified = new HashMap<>();  // config name -> its content, for valid ones

  Remote(Driver driver, Url url, String key) {
    this.driver = driver;
    this.url = url;
    this.seal = new ConfigFile(key);
  }

  // ---------------------------------------------------------------- reading

  private List<Names.Name> names(String prefix) throws IOException {
    List<Names.Name> r = new ArrayList<>();
    for (String s : driver.list(prefix)) {
      Names.Name n = Names.parse(s);
      if (n != null && s.startsWith(prefix)) r.add(n);
    }
    return r;
  }

  /**
   * Every file of the backup: the configs at the root, and the tomes and indexes in the folders of the full backups.
   * A listing returns the entries of one level; what is a folder is told by its name alone, nothing else is looked at.
   */
  private List<Names.Name> allNames() throws IOException {
    List<Names.Name> r = new ArrayList<>();
    for (String entry : driver.list("")) {
      Names.Name n = Names.parse(entry);
      if (n != null) {
        r.add(n);
        continue;
      }
      String folder = Names.folder(entry);
      if (folder == null) continue;
      for (String inside : driver.list(folder + "/")) {
        Names.Name d = Names.parse(inside);
        if (d != null && folder.equals(d.folder())) r.add(d);
      }
    }
    return r;
  }

  /** The highest revision that has a complete config, with those configs; null if there is none. */
  private Level findLevel(int below) throws IOException {
    TreeMap<Integer, List<String>> byRevision = new TreeMap<>();
    for (Names.Name n : names(Names.CONFIG_PREFIX))
      if (n.revision() < below) byRevision.computeIfAbsent(n.revision(), k -> new ArrayList<>()).add(n.name());
    for (var e : byRevision.descendingMap().entrySet()) {
      List<Candidate> cs = complete(e.getValue());
      if (!cs.isEmpty()) return new Level(e.getKey(), cs);
    }
    return null;
  }

  private List<Candidate> complete(List<String> files) throws IOException {
    List<Candidate> cs = new ArrayList<>();
    for (String f : files) {
      ConfigFile.Sealed s = ConfigFile.wellFormed(driver.download(f, ConfigFile.MAX_SIZE));
      if (s != null) cs.add(new Candidate(f, s));
    }
    return cs;
  }

  /** The valid ones of the configs, parsed; the seal is checked before the yaml is looked at. */
  private List<Snapshot> valid(Level level) {
    List<Snapshot> r = new ArrayList<>();
    for (Candidate c : level.configs()) {
      Conf conf = verified.get(c.name());
      if (conf == null && seal.verify(c.sealed())) {
        try {
          conf = Conf.parse(new String(c.sealed().covered(), StandardCharsets.UTF_8));
        } catch (RiverException e) {
          conf = null;  // sealed by someone who has the key, but garbage
        }
        if (conf != null && conf.revision != level.revision()) conf = null;
        if (conf != null) verified.put(c.name(), conf);
      }
      if (conf != null) r.add(new Snapshot(conf, level.revision(), c.name()));
    }
    return r;
  }

  private RiverException unverifiable(Level level) {
    boolean salted = level.configs().stream().anyMatch(c -> !c.sealed().salt().isEmpty());
    if (salted && !seal.keyed())
      return new RiverException("backup is encrypted: pass the key in river_key (or river_key_file) environment variable");
    return new RiverException("cannot verify the config of revision " + level.revision() + " at " + url + ": "
        + (seal.keyed() ? "wrong key (river_key), or the backup is not encrypted, or the config is damaged"
            : "the config is damaged"));
  }

  /** The latest config, null if there is no backup. A damaged or unverifiable latest revision is an error. */
  Snapshot latestOrNull() throws IOException {
    Level level = findLevel(Integer.MAX_VALUE);
    if (level == null) return null;
    List<Snapshot> v = valid(level);
    if (v.isEmpty()) throw unverifiable(level);
    return v.get(0);
  }

  /** True if the url holds a backup, valid or not (a complete config exists). */
  boolean exists() throws IOException {
    return findLevel(Integer.MAX_VALUE) != null;
  }

  Snapshot latest() {
    try {
      Snapshot s = latestOrNull();
      if (s == null) throw new RiverException("no backup at " + url + " (wrong url? not created?)");
      return s;
    } catch (IOException e) {
      throw new RiverException("cannot read the backup at " + url + ": " + e.getMessage(), e);
    }
  }

  // ---------------------------------------------------------------- writing

  /**
   * Commits {@code next} (its revision is the one to commit). Everything it refers to must already be stored.
   * Fails if somebody committed that revision or a later one in the meantime.
   */
  void commit(Conf next) throws IOException {
    for (Names.Name n : names(Names.CONFIG_PREFIX)) {
      if (n.revision() < next.revision) continue;
      ConfigFile.Sealed s = ConfigFile.wellFormed(driver.download(n.name(), ConfigFile.MAX_SIZE));
      if (s != null)
        throw new RiverException("revision " + n.revision() + " was committed by someone else while this run was "
            + "working, not committing (is another backup running?)");
    }
    String name = Names.config(next.revision);
    driver.upload(name, seal.seal(next.dump()));
    verified.put(name, next);
  }

  /** Names a config refers to. */
  private static void referenced(Conf c, Set<String> into) {
    for (Conf.FullBackup g : c.fullBackups) {
      if (g.index != null) into.add(g.index);
      into.addAll(g.tomes);
    }
  }

  /**
   * Removes what no config of the latest and the previous revision refers to, but only files of older revisions:
   * newer ones may belong to a run that has not committed yet. Failures are reported to {@code warn} and do not stop
   * the cleanup. Nothing is removed if the files of the latest config are not all there.
   */
  void cleanup(int revision, Consumer<String> warn) {
    try {
      Level latest = findLevel(revision + 1);
      if (latest == null || latest.revision() != revision) return;
      Set<String> keep = new HashSet<>();
      for (Snapshot s : valid(latest)) {
        keep.add(s.name());
        referenced(s.conf(), keep);
      }
      Level previous = findLevel(revision);
      if (previous != null && previous.revision() == revision - 1) {
        for (Snapshot s : valid(previous)) {
          keep.add(s.name());
          referenced(s.conf(), keep);
        }
      }
      List<Names.Name> all = allNames();
      Set<String> present = new HashSet<>();
      for (Names.Name n : all) present.add(n.name());
      for (String f : keep) {
        if (!present.contains(f) && Names.parse(f) != null) {
          warn.accept("warning: " + f + " is missing, not cleaning up");
          return;
        }
      }
      List<Names.Name> doomed = new ArrayList<>();
      for (Names.Name n : all) if (n.revision() < revision && !keep.contains(n.name())) doomed.add(n);
      // configs first, so that no valid config ever refers to a file that is gone; then indexes, then tomes
      doomed.sort((a, b) -> Integer.compare(order(a), order(b)));
      for (Names.Name n : doomed) {
        try {
          driver.delete(n.name());
        } catch (IOException e) {
          warn.accept("warning: " + e.getMessage());
        }
      }
    } catch (IOException | RiverException e) {
      warn.accept("warning: cleanup skipped: " + e.getMessage());
    }
  }

  private static int order(Names.Name n) {
    return n.config() ? 0 : n.index() ? 1 : 2;
  }

  /** Every file of the backup, configs last. */
  void deleteAll(Consumer<String> progress) throws IOException {
    List<Names.Name> data = allNames();
    List<Names.Name> configs = new ArrayList<>();
    for (Names.Name n : data) if (n.config()) configs.add(n);
    data.removeIf(Names.Name::config);
    data.sort((a, b) -> Integer.compare(order(a), order(b)));
    configs.sort((a, b) -> Integer.compare(a.revision(), b.revision()));  // the newest stays the longest
    for (Names.Name n : data) driver.delete(n.name());
    for (Names.Name n : configs) {
      progress.accept(n.name());
      driver.delete(n.name());
    }
  }
}
