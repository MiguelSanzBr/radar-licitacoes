package br.dev.miguelsanzbr.radar.ops.application.port;

import br.dev.miguelsanzbr.radar.ops.domain.DeadMessage;
import br.dev.miguelsanzbr.radar.ops.domain.DlqCount;
import java.time.Instant;
import java.util.List;

/** The dead messages kept for the operator. */
public interface DlqMessages {

  /** A message waiting to go back to its original topic. */
  record Pending(long id, String key, String value) {}

  /** Stores the message; the same original record failed by the same group is stored once. */
  void record(DeadMessage message);

  /**
   * Not yet reprocessed messages of a dead-letter topic, at most {@code limit}: the ones never
   * tried first, then the ones whose last attempt failed, each oldest first; {@code id} narrows to
   * one.
   */
  List<Pending> pending(String topic, Long id, int limit);

  void markReprocessed(long id, Instant at);

  /** Keeps the message pending and remembers why the last attempt failed. */
  void markFailed(long id, String result);

  /**
   * Not yet reprocessed messages per dead-letter topic and group; a topic without any is absent.
   */
  List<DlqCount> unreprocessed();
}
