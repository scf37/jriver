package io.github.scf37.river;

/** A failure to report to the user; {@link #exitCode} is the process exit code. */
public class RiverException extends RuntimeException {
  public static final int ERROR = 2;
  public static final int LOCKED = 3;

  public final int exitCode;

  public RiverException(String message) {
    this(message, ERROR);
  }

  public RiverException(String message, int exitCode) {
    super(message);
    this.exitCode = exitCode;
  }

  public RiverException(String message, Throwable cause) {
    super(message, cause);
    this.exitCode = ERROR;
  }
}
