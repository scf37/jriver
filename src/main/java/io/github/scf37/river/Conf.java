package io.github.scf37.river;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Contents of {@code river.conf}: the configuration (edited by the user) and the state (kept by
 * river): generations and their versions, and the trash.
 */
final class Conf {
  /** Version {@code n} of a generation (the n-th version in its index). */
  record Version(int n, String time) {}

  /** One chain of archive versions: a full backup followed by incremental ones. */
  static final class Generation {
    final String name;
    String index;  // current index file, relative to the backup root; null before the first commit
    final List<Version> versions = new ArrayList<>();

    Generation(String name) {
      this.name = name;
    }
  }

  List<String> paths = new ArrayList<>();
  List<String> exclude = new ArrayList<>();
  List<String> includeOnly = new ArrayList<>();
  String method = "1";
  int keepIncrementalBackupCount = 30;
  int keepFullBackupCount = 3;
  boolean encrypted;
  final List<Generation> generations = new ArrayList<>();
  /**
   * Files and directories that nothing refers to any more and that are still to be deleted: written to river.conf
   * in the same commit that stops referring to them, so that a failed or skipped delete is retried by later runs
   * (drivers cannot list, the trash is the only way to find them).
   */
  final List<String> trash = new ArrayList<>();

  private static Yaml yaml() {
    DumperOptions o = new DumperOptions();
    o.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    o.setSplitLines(false);
    return new Yaml(new SafeConstructor(new LoaderOptions()), new org.yaml.snakeyaml.representer.Representer(o), o);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> parseMap(String text, String what) {
    Object o;
    try {
      o = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    } catch (RuntimeException e) {
      throw new RiverException(what + ": invalid yaml: " + e.getMessage());
    }
    if (o == null) return new LinkedHashMap<>();
    if (!(o instanceof Map)) throw new RiverException(what + ": expected a mapping");
    return (Map<String, Object>) o;
  }

  /** Parses a user supplied config file: configuration only, state is ignored. */
  static Conf parseConfig(String text, String what) {
    Conf c = new Conf();
    c.readConfig(parseMap(text, what), what);
    return c;
  }

  /** Parses river.conf: configuration and state. */
  static Conf parse(String text) {
    Map<String, Object> m = parseMap(text, "river.conf");
    Conf c = new Conf();
    c.readConfig(m, "river.conf");
    Object gens = m.get("generations");
    if (gens != null) {
      for (Object g : list(gens, "generations")) {
        Map<String, Object> gm = map(g, "generations[]");
        Generation gen = new Generation(str(gm.get("name"), "generations[].name"));
        Object idx = gm.get("index");
        gen.index = idx == null ? null : str(idx, "generations[].index");
        for (Object v : list(gm.get("versions"), "versions")) {
          Map<String, Object> vm = map(v, "versions[]");
          gen.versions.add(new Version(integer(vm.get("n"), "versions[].n"), str(vm.get("time"), "versions[].time")));
        }
        c.generations.add(gen);
      }
    }
    c.trash.addAll(strings(m.get("trash"), "trash"));
    return c;
  }

  private void readConfig(Map<String, Object> m, String what) {
    paths = strings(m.get("paths"), "paths");
    exclude = strings(m.get("exclude"), "exclude");
    includeOnly = strings(m.get("include_only"), "include_only");
    if (m.get("method") != null) method = String.valueOf(m.get("method"));
    if (m.get("keep_incremental_backup_count") != null)
      keepIncrementalBackupCount = integer(m.get("keep_incremental_backup_count"), "keep_incremental_backup_count");
    if (m.get("keep_full_backup_count") != null)
      keepFullBackupCount = integer(m.get("keep_full_backup_count"), "keep_full_backup_count");
    if (m.get("encrypted") != null) {
      if (!(m.get("encrypted") instanceof Boolean b)) throw new RiverException("encrypted must be true or false");
      encrypted = b;
    }
    if (keepIncrementalBackupCount < 0) throw new RiverException("keep_incremental_backup_count must not be negative");
    if (keepFullBackupCount < 1) throw new RiverException("keep_full_backup_count must be at least 1");
    if (method.isBlank()) throw new RiverException("method must not be empty");
  }

  /** Only the configuration part. */
  Map<String, Object> configMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("paths", paths);
    m.put("exclude", exclude);
    m.put("include_only", includeOnly);
    m.put("method", method);
    m.put("keep_incremental_backup_count", keepIncrementalBackupCount);
    m.put("keep_full_backup_count", keepFullBackupCount);
    m.put("encrypted", encrypted);
    return m;
  }

  Map<String, Object> stateMap() {
    List<Object> gens = new ArrayList<>();
    for (Generation g : generations) {
      Map<String, Object> gm = new LinkedHashMap<>();
      gm.put("name", g.name);
      gm.put("index", g.index);
      List<Object> vs = new ArrayList<>();
      for (Version v : g.versions) {
        Map<String, Object> vm = new LinkedHashMap<>();
        vm.put("n", v.n());
        vm.put("time", v.time());
        vs.add(vm);
      }
      gm.put("versions", vs);
      gens.add(gm);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("generations", gens);
    m.put("trash", trash);
    return m;
  }

  String dumpConfig() {
    return yaml().dump(configMap());
  }

  /** river.conf text. */
  String dump() {
    Map<String, Object> m = configMap();
    m.putAll(stateMap());
    return yaml().dump(m);
  }

  /** Identifies the state: changes whenever a backup is committed or generations roll. */
  String stateFingerprint() {
    return yaml().dump(stateMap());
  }

  /** Same state, new configuration. */
  Conf withConfigFrom(Conf cfg) {
    Conf c = new Conf();
    c.paths = cfg.paths;
    c.exclude = cfg.exclude;
    c.includeOnly = cfg.includeOnly;
    c.method = cfg.method;
    c.keepIncrementalBackupCount = cfg.keepIncrementalBackupCount;
    c.keepFullBackupCount = cfg.keepFullBackupCount;
    c.encrypted = cfg.encrypted;
    c.generations.addAll(generations);
    c.trash.addAll(trash);
    return c;
  }

  /** True if the state refers to this file or directory (a generation or an index): it must not be deleted. */
  boolean refers(String rel) {
    for (Generation g : generations) if (rel.equals(g.name) || rel.equals(g.index)) return true;
    return false;
  }

  Generation lastGeneration() {
    return generations.isEmpty() ? null : generations.get(generations.size() - 1);
  }

  Generation generation(String name) {
    for (Generation g : generations) if (g.name.equals(name)) return g;
    return null;
  }

  private static List<?> list(Object o, String name) {
    if (o == null) return List.of();
    if (!(o instanceof List<?> l)) throw new RiverException(name + " must be a list");
    return l;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o, String name) {
    if (!(o instanceof Map)) throw new RiverException(name + " must be a mapping");
    return (Map<String, Object>) o;
  }

  private static String str(Object o, String name) {
    if (o == null) throw new RiverException(name + " is missing");
    return String.valueOf(o);
  }

  private static int integer(Object o, String name) {
    if (!(o instanceof Integer i)) throw new RiverException(name + " must be an integer");
    return i;
  }

  private static List<String> strings(Object o, String name) {
    List<String> r = new ArrayList<>();
    for (Object e : list(o, name)) {
      if (!(e instanceof String s)) throw new RiverException(name + " must only contain strings");
      r.add(s);
    }
    return r;
  }
}
