package br.dev.miguelsanzbr.radar.search.application;

import java.util.List;
import java.util.function.Consumer;

/** The procurement events as the topic holds them, readable again from the start. */
public interface EventLog {

  /**
   * Fixes where the log ends now and opens a reader at its start. It fails here, before anything is
   * wiped, when the log cannot be reached.
   */
  Snapshot snapshot();

  interface Snapshot extends AutoCloseable {

    /**
     * Hands the events up to the end fixed by the snapshot to {@code sink}, in batches of at most
     * {@code batchSize}. Returns how many records it read, those it could not decode included.
     */
    long replay(int batchSize, Consumer<List<OffsetEvent>> sink);

    @Override
    void close();
  }
}
