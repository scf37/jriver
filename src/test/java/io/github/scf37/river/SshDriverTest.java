package io.github.scf37.river;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ssh driver against a fake {@code ssh} that runs the remote command locally, in a process of its own (not a
 * child of the client): like a real server, it sees the end of its input when the client dies.
 */
class SshDriverTest {
  @TempDir Path tmp;
  Path bin;
  Path dir;

  @BeforeEach
  void setUp() throws IOException {
    bin = Files.createDirectories(tmp.resolve("bin"));
    dir = Files.createDirectories(tmp.resolve("remote"));
    Path ssh = bin.resolve("ssh");
    Files.writeString(ssh, """
        #!/bin/sh
        for a; do cmd=$a; done
        d=$(mktemp -d)
        mkfifo "$d/in"
        setsid -f sh -c 'exec < "$1/in"; sh -c "$2"; echo $? > "$1/rc.tmp"; mv "$1/rc.tmp" "$1/rc"' remote "$d" "$cmd"
        cat > "$d/in"
        while [ ! -f "$d/rc" ]; do sleep 0.05; done
        exit "$(cat "$d/rc")"
        """);
    Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));
  }

  private Process upload(String file) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(Path.of("drivers/ssh/upload").toAbsolutePath().toString(),
        "me@host:" + dir, file);
    Map<String, String> env = pb.environment();
    env.put("PATH", bin + ":" + env.get("PATH"));
    env.remove("SSH_OPTS");
    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
    return pb.start();
  }

  private static byte[] data() {
    byte[] b = new byte[300_000];
    new Random(1).nextBytes(b);
    return b;
  }

  @Test
  void completeUploadIsPublishedAsSent() throws Exception {
    byte[] b = data();
    Process p = upload("g/arc-1");
    try (OutputStream out = p.getOutputStream()) {
      out.write(b);
    }
    assertEquals(0, p.waitFor());
    assertArrayEquals(b, Files.readAllBytes(dir.resolve("g/arc-1")));
    try (var files = Files.list(dir.resolve("g"))) {
      assertEquals(1, files.count(), "no temp file left");
    }
  }

  @Test
  void killedUploadIsNotPublished() throws Exception {
    Files.createDirectories(dir.resolve("g"));
    Files.writeString(dir.resolve("g/arc-1"), "committed");
    Process p = upload("g/arc-1");
    OutputStream out = p.getOutputStream();
    out.write(data(), 0, 100_000);
    out.flush();
    Thread.sleep(300);
    Driver.kill(p);
    p.waitFor(10, TimeUnit.SECONDS);
    Thread.sleep(500);  // the remote side goes on until it sees the end of its input
    assertEquals("committed", Files.readString(dir.resolve("g/arc-1")));
    try (var files = Files.list(dir.resolve("g"))) {
      assertEquals(1, files.count(), "no temp file left");
    }
  }

  @Test
  void dataThatEndsEarlyIsNotPublished() throws Exception {
    // the client side ends normally but the remote gets less than all: no end marker, no file
    Process p = upload("arc-2");
    p.getOutputStream().close();
    assertEquals(0, p.waitFor(), "an empty upload is complete");
    assertEquals(0, Files.size(dir.resolve("arc-2")));
    Path cut = bin.resolve("ssh");
    Files.writeString(cut, Files.readString(cut).replace("cat > \"$d/in\"", "head -c 1000 > \"$d/in\""));
    Process q = upload("arc-3");
    try (OutputStream out = q.getOutputStream()) {
      out.write(data());
    } catch (IOException e) {
      // the fake connection stopped reading
    }
    assertNotEquals(0, q.waitFor());
    assertEquals(false, Files.exists(dir.resolve("arc-3")));
  }
}
