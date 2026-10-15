package io.github.scf37.river;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Main {
  private static final String HELP = """
      Usage: river [-v] <command> [arguments]

      Incremental backups to any remote place that can be reached with three scripts.
      A backup is addressed by <url>: <driver>:<driver specific path>. Drivers are the
      directories under drivers/ next to this program (upload, download, delete scripts).

      Commands:
        init [file]                          write an example config to file (stdout if omitted)
        create <url> [config]                create a backup with this config (stdin if omitted or -)
        config <url> [config]                show the config of a backup; with a file, replace it
        backup <url>                         add a new version of the paths from the config
        list <url> [--files <version>]       list versions; or the files of one version
        restore <url> [<version>] [--to <dir>] [--files <pattern>...]
                                             restore (default: latest version) in place or under dir
        verify <url> [<version>]             check that the version can be restored, write nothing
        delete <url> --yes                   delete the whole backup, irreversibly
        unlock <url>                         force-release the lock of a crashed backup
        help                                 this text

      A version is <generation>:<n> as shown by list, or 'latest'.
      Encrypted backups need the key in the river_key environment variable
      (or river_key_file: path of a file with the key).
      -v shows what the archiver does; otherwise river only speaks on problems.
      Exit codes: 0 ok, 1 ok but some files had problems, 2 error, 3 backup is locked.
      """;

  private Main() {}

  public static void main(String[] args) {
    Path home = home();
    int rc = run(args, System.getenv(), home, System.in, System.out, System.err);
    System.out.flush();
    System.exit(rc);
  }

  static Path home() {
    String h = System.getProperty("river.home");
    if (h != null) return Path.of(h);
    try {
      Path jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      return jar.toAbsolutePath().getParent();
    } catch (Exception e) {
      return Path.of("").toAbsolutePath();
    }
  }

  /** The program without System.exit and global state, for tests. */
  static int run(String[] argv, Map<String, String> env, Path home, InputStream in, PrintStream out, PrintStream err) {
    List<String> args = new ArrayList<>(List.of(argv));
    boolean verbose = false;
    if (!args.isEmpty() && args.get(0).equals("-v")) {
      verbose = true;
      args.remove(0);
    }
    if (args.isEmpty() || args.get(0).equals("help") || args.get(0).equals("-h") || args.get(0).equals("--help")) {
      (args.isEmpty() ? err : out).print(HELP);
      return args.isEmpty() ? 2 : 0;
    }
    String cmd = args.remove(0);
    Commands c = new Commands(home, env, out, err, verbose);
    try {
      return switch (cmd) {
        case "init" -> {
          count(cmd, args, 0, 1);
          yield c.init(args.isEmpty() ? null : args.get(0));
        }
        case "create" -> {
          count(cmd, args, 1, 2);
          yield c.create(Url.parse(args.get(0)), args.size() > 1 ? args.get(1) : null, in);
        }
        case "config" -> {
          count(cmd, args, 1, 2);
          yield c.config(Url.parse(args.get(0)), args.size() > 1 ? args.get(1) : null, in);
        }
        case "backup" -> {
          count(cmd, args, 1, 1);
          yield c.backup(Url.parse(args.get(0)));
        }
        case "list" -> {
          String files = takeOption(args, "--files");
          count(cmd, args, 1, 1);
          yield c.list(Url.parse(args.get(0)), files);
        }
        case "restore" -> {
          List<String> files = new ArrayList<>();
          int fi = args.indexOf("--files");
          if (fi >= 0) {
            files.addAll(args.subList(fi + 1, args.size()));
            args.subList(fi, args.size()).clear();
            if (files.isEmpty()) throw new RiverException("--files needs at least one pattern");
          }
          String to = takeOption(args, "--to");
          count(cmd, args, 1, 2);
          yield c.restore(Url.parse(args.get(0)), args.size() > 1 ? args.get(1) : null, to, files, false);
        }
        case "verify" -> {
          count(cmd, args, 1, 2);
          yield c.restore(Url.parse(args.get(0)), args.size() > 1 ? args.get(1) : null, null, List.of(), true);
        }
        case "delete" -> {
          boolean yes = args.remove("--yes");
          count(cmd, args, 1, 1);
          if (!yes) throw new RiverException("this deletes the whole backup irreversibly; pass --yes to do it");
          yield c.delete(Url.parse(args.get(0)));
        }
        case "unlock" -> {
          count(cmd, args, 1, 1);
          yield c.unlock(Url.parse(args.get(0)));
        }
        default -> throw new RiverException("unknown command '" + cmd + "' (river help)");
      };
    } catch (RiverException e) {
      err.println("river: " + e.getMessage());
      return e.exitCode;
    } catch (RuntimeException e) {
      err.println("river: unexpected error: " + e);
      if (verbose) e.printStackTrace(err);
      return RiverException.ERROR;
    }
  }

  private static void count(String cmd, List<String> args, int min, int max) {
    if (args.size() < min) throw new RiverException("not enough arguments for " + cmd + " (river help)");
    if (args.size() > max) throw new RiverException("too many arguments for " + cmd + " (river help)");
  }

  private static String takeOption(List<String> args, String name) {
    int i = args.indexOf(name);
    if (i < 0) return null;
    if (i + 1 >= args.size()) throw new RiverException(name + " needs a value");
    String v = args.get(i + 1);
    args.subList(i, i + 2).clear();
    return v;
  }
}
