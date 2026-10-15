package io.github.scf37.river;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A storage driver: the directory {@code drivers/<name>} next to the river executable with three
 * executables, called as {@code <op> <url path> <relative path>}:
 *
 * <ul>
 *   <li>{@code upload}: reads the file from stdin
 *   <li>{@code download}: writes the file to stdout
 *   <li>{@code delete}: deletes the file or directory (recursively); a missing file is no error
 * </ul>
 *
 * A non-zero exit code is a failure; stderr is passed through to the user.
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
    Process p = start("download", rel, false);
    byte[] b;
    try (InputStream in = p.getInputStream()) {
      b = in.readAllBytes();
    }
    check(p, "download " + rel);
    return b;
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
