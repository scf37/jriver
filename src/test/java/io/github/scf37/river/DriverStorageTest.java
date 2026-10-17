package io.github.scf37.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriverStorageTest {
  @TempDir Path tmp;
  Fixture f;
  Driver driver;

  @BeforeEach
  void setUp() throws IOException {
    f = new Fixture(tmp);
    Files.createDirectories(f.remote);
    driver = new Driver(f.home, Url.parse(f.url));
  }

  /** A guard that holds until {@link #lost} is set. */
  static final class Switch implements Guard {
    final AtomicBoolean lost = new AtomicBoolean();

    @Override
    public void verify() throws IOException {
      if (lost.get()) throw new IOException("lock lost: test");
    }

    @Override
    public void beforeWrite() throws IOException {
      verify();
    }

    @Override
    public Runnable onLost(Runnable abort) {
      return () -> {};
    }
  }

  @Test
  void uploadIsPublishedOnClose() throws IOException {
    DriverStorage s = new DriverStorage(driver, new Switch());
    try (OutputStream out = s.write("x")) {
      out.write("new".getBytes(StandardCharsets.UTF_8));
    }
    assertEquals("new", Files.readString(f.remote.resolve("x")));
  }

  @Test
  void guardLostAfterTheLastWriteKeepsTheUploadFromBeingPublished() throws IOException {
    Files.writeString(f.remote.resolve("x"), "old");
    Switch guard = new Switch();
    DriverStorage s = new DriverStorage(driver, guard);
    OutputStream out = s.write("x");
    out.write("new".getBytes(StandardCharsets.UTF_8));
    guard.lost.set(true);  // e.g. frozen here while another process took over
    assertThrows(IOException.class, out::close);
    assertEquals("old", Files.readString(f.remote.resolve("x")), "existing file untouched");
    try (var files = Files.list(f.remote)) {
      assertEquals(1, files.count(), "no temp file left either: the driver was killed with its children");
    }
  }
}
