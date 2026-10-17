package io.github.scf37.river;

import java.io.IOException;

/** Checked by the storage before it opens a blob for writing, before every write and before it publishes the blob. */
interface Guard {
  /**
   * Fully checks the lock: before a blob is opened, and before it is published (closing the upload makes the
   * driver replace an existing blob of that name).
   */
  void verify() throws IOException;

  /** Before every chunk of data: cheap local check. */
  void beforeWrite() throws IOException;

  /** Registers an action to run if the guard is lost; returns the way to unregister it. */
  Runnable onLost(Runnable abort);

  Guard NONE = new Guard() {
    @Override
    public void verify() {}

    @Override
    public void beforeWrite() {}

    @Override
    public Runnable onLost(Runnable abort) {
      return () -> {};
    }
  };
}
