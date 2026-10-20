package io.github.scf37.river;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A storage driver: the directory {@code drivers/<name>} next to the river executable with four
 * executables, called as {@code <op> <url path> <argument>}:
 *
 * <ul>
 *   <li>{@code upload <file>}: reads the file from stdin; exit code 0 only if all of it was stored
 *   <li>{@code download <file>}: writes the file to stdout
 *   <li>{@code delete <file>}: deletes the file; a missing file is no error
 *   <li>{@code list <prefix>}: the entries (files and folders) starting with prefix, one per line, as
 *       {@code name} or {@code folder/name}; a prefix with a folder part lists inside that folder
 * </ul>
 *
 * Names are {@code name} or {@code folder/name}, one level. A non-zero exit code is a failure; stderr is passed through to the user.
 */
final class Driver {
  private final Path dir;
  private final String path;

  Driver(Path home, Url url) {
    this.dir = home.resolve("drivers").resolve(url.driver());
    this.path = url.path();
    if (!Files.isDirectory(dir))
      throw new RiverException("unknown driver '" + url.driver() + "': no directory " + dir);
  }

  Process start(String op, String rel, boolean stdin) throws IOException {
    Path script = dir.resolve(op);
    if (!Files.isExecutable(script)) throw new IOException("driver script is not executable: " + script);
    ProcessBuilder pb = new ProcessBuilder(script.toString(), path, rel);
    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
    if (!stdin) pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    return pb.start();
  }

  static void check(Process p, String what) throws IOException {
    try {
      int rc = p.waitFor();
      if (rc != 0) throw new IOException(what + " failed (exit code " + rc + ")");
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException(what + " interrupted", e);
    }
  }

  byte[] download(String rel) throws IOException {
    return download(rel, Integer.MAX_VALUE - 8);
  }

  /** Downloads a small file; more than {@code max} bytes is an error (the storage is not trusted). */
  byte[] download(String rel, int max) throws IOException {
    Process p = start("download", rel, false);
    byte[] b;
    try (InputStream in = p.getInputStream()) {
      b = in.readNBytes(max + 1);
      if (b.length > max) {
        p.destroyForcibly();
        throw new IOException("download " + rel + ": file is larger than " + max + " bytes");
      }
    }
    check(p, "download " + rel);
    return b;
  }

  /** Entries starting with prefix (one level; folders may carry a trailing slash). A failed listing is an error, an empty one is not. */
  List<String> list(String prefix) throws IOException {
    Process p = start("list", prefix, false);
    byte[] b;
    try (InputStream in = p.getInputStream()) {
      b = in.readAllBytes();
    }
    check(p, "list " + prefix);
    List<String> r = new ArrayList<>();
    for (String line : new String(b, StandardCharsets.UTF_8).split("\n")) if (!line.isEmpty()) r.add(line);
    return r;
  }

  void upload(String rel, byte[] data) throws IOException {
    Process p = start("upload", rel, true);
    try (var out = p.getOutputStream()) {
      p.getInputStream().close();
      out.write(data);
    } catch (IOException e) {
      p.destroyForcibly();
      throw e;
    }
    check(p, "upload " + rel);
  }

  void delete(String rel) throws IOException {
    Process p = start("delete", rel, false);
    p.getInputStream().close();
    check(p, "delete " + rel);
  }
}
