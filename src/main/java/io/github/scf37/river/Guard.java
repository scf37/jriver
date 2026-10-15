package io.github.scf37.river;

import java.io.IOException;

/** Checked by the storage before it opens a blob for writing and before every write. */
interface Guard {
  /** Before a blob is opened (this truncates an existing one): fully checks the lock. */
  void beforeOpen() throws IOException;

  /** Before every chunk of data: cheap local check. */
  void beforeWrite() throws IOException;

  /** Registers an action to run if the guard is lost; returns the way to unregister it. */
  Runnable onLost(Runnable abort);

  Guard NONE = new Guard() {
    @Override
    public void beforeOpen() {}

    @Override
    public void beforeWrite() {}

    @Override
    public Runnable onLost(Runnable abort) {
      return () -> {};
    }
  };
}
