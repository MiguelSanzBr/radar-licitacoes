package br.dev.miguelsanzbr.radar.ops.application;

/** A republish failed: the run stopped, and {@link #done()} messages had already gone back. */
public class ReprocessInterruptedException extends RuntimeException {

  private final int done;

  public ReprocessInterruptedException(int done, String reason) {
    super(reason);
    this.done = done;
  }

  public int done() {
    return done;
  }
}
