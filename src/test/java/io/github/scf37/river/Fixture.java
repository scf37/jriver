package io.github.scf37.river;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** A temp directory with river home (drivers), a source tree, a remote and helpers to run river. */
final class Fixture {
  record Result(int rc, String out, String err) {}

  final Path root;
  final Path home;
  final Path src;
  final Path remote;
  final Map<String, String> env = new HashMap<>();
  final String url;

  Fixture(Path root) throws IOException {
    this.root = root;
    home = root.resolve("home");
    src = root.resolve("src");
    remote = root.resolve("remote");
    Files.createDirectories(src);
    copyTree(Path.of("drivers"), home.resolve("drivers"));
    env.put("river_lock_dir", root.resolve("locks").toString());
    ConfigFile.scryptN = 1 << 8;  // the real cost is tested in ConfigFileTest
    url = "local:" + remote;
  }

  static void copyTree(Path from, Path to) throws IOException {
    try (Stream<Path> s = Files.walk(from)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        Path t = to.resolve(from.relativize(p).toString());
        if (Files.isDirectory(p)) Files.createDirectories(t);
        else {
          Files.copy(p, t);
          Files.setPosixFilePermissions(t, Files.getPosixFilePermissions(p));
        }
      }
    }
  }

  /**
   * A driver that behaves like local but fails while the marker file {@code fail-pattern} holds a glob of
   * {@code <op>:<name>}, and runs the shell script {@code hook.sh} (if there is one) when {@code hook-pattern} matches.
   */
  void addFlakyDriver() throws IOException {
    Path d = home.resolve("drivers/flaky");
    Files.createDirectories(d);
    Path marker = root.resolve("fail-pattern");
    Path hookMarker = root.resolve("hook-pattern");
    Path hook = root.resolve("hook.sh");
    for (String op : List.of("upload", "download", "delete", "list")) {
      Path s = d.resolve(op);
      Files.writeString(s, "#!/bin/sh\nset -eu\nif [ -f '" + marker + "' ]; then\n  pat=$(cat '" + marker + "')\n"
          + "  case \"" + op + ":$2\" in $pat) echo \"flaky: injected failure\" >&2; exit 1 ;; esac\nfi\n"
          + "if [ -f '" + hookMarker + "' ]; then\n  pat=$(cat '" + hookMarker + "')\n"
          + "  case \"" + op + ":$2\" in $pat) sh '" + hook + "' ;; esac\nfi\n"
          + "exec '" + home.resolve("drivers/local/" + op) + "' \"$@\"\n");
      Files.setPosixFilePermissions(s, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE));
    }
  }

  void failWhen(String glob) throws IOException {
    Files.writeString(root.resolve("fail-pattern"), glob);
  }

  /** Runs the shell script (with $R = the remote directory) when the flaky driver does an operation matching glob. */
  void hookWhen(String glob, String script) throws IOException {
    Files.writeString(root.resolve("hook.sh"), "R='" + remote + "'\n" + script + "\n");
    Files.writeString(root.resolve("hook-pattern"), glob);
  }

  void failNever() throws IOException {
    Files.deleteIfExists(root.resolve("fail-pattern"));
    Files.deleteIfExists(root.resolve("hook-pattern"));
  }

  /** Sorted relative names of all files of the backup (folders included, as dir/name). */
  List<String> remoteNames() throws IOException {
    if (!Files.exists(remote)) return List.of();
    try (Stream<Path> s = Files.walk(remote)) {
      return s.filter(Files::isRegularFile).map(p -> remote.relativize(p).toString()).sorted().toList();
    }
  }

  /** How many files of the backup have a name (without the folder) that starts with prefix. */
  long count(String prefix) throws IOException {
    return remoteNames().stream().filter(n -> n.substring(n.lastIndexOf('/') + 1).startsWith(prefix)).count();
  }

  /** Folders at the backup root. */
  List<String> folders() throws IOException {
    if (!Files.exists(remote)) return List.of();
    try (Stream<Path> s = Files.list(remote)) {
      return s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  Result river(String... args) {
    return riverStdin("", args);
  }

  Result riverStdin(String stdin, String... args) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    ByteArrayOutputStream e = new ByteArrayOutputStream();
    int rc = Main.run(args, env, home, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
        new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
    return new Result(rc, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
  }

  String config(String extra) {
    return "paths: ['" + src + "']\nkeep_incremental_backup_count: 2\nkeep_full_backup_count: 3\n" + extra;
  }

  /** Creates the backup at {@link #url} with this config. */
  void create(String config) throws IOException {
    Path c = root.resolve("conf-" + System.nanoTime() + ".yaml");
    Files.writeString(c, config);
    Result r = river("create", url, c.toString());
    if (r.rc() != 0) throw new AssertionError("create failed: " + r);
  }

  void write(String name, String content) throws IOException {
    Path p = src.resolve(name);
    Files.createDirectories(p.getParent());
    Files.writeString(p, content);
  }

  void backup() {
    Result r = river("backup", url);
    if (r.rc() != 0) throw new AssertionError("backup failed: " + r);
  }

  List<String> versions() {
    Result r = river("list", url);
    if (r.rc() != 0) throw new AssertionError("list failed: " + r);
    return r.out().lines().map(l -> l.substring(0, l.indexOf('\t'))).toList();
  }

  /** Sorted relative names (under the restored src) of all regular files below {@code dir}. */
  List<String> files(Path dir) throws IOException {
    if (!Files.exists(dir)) return List.of();
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString()).sorted().toList();
    }
  }

  /** Files of version {@code v} restored under a fresh directory; names relative to the source dir. */
  List<String> restoredFiles(String v) throws IOException {
    Path to = root.resolve("restore-" + System.nanoTime());
    Result r = river("restore", url, v, "--to", to.toString());
    if (r.rc() != 0) throw new AssertionError("restore failed: " + r);
    return files(to.resolve(src.toString().substring(1)));
  }
}
