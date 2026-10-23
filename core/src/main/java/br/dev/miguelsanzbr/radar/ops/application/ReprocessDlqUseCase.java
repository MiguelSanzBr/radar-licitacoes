package br.dev.miguelsanzbr.radar.ops.application;

import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages;
import br.dev.miguelsanzbr.radar.ops.application.port.Republisher;
import java.time.Clock;
import java.util.Set;

/**
 * Sends the dead messages of a dead-letter topic back to the topic they came from, same key and
 * same value. Nothing here prevents a message from being handled twice (two operators at once, a
 * crash between the send and the mark): the inboxes of the consumers absorb the duplicate.
 */
public class ReprocessDlqUseCase {

  private static final String SUFFIX = ".dlq";

  /** How many went back, and whether messages are still pending for another call. */
  public record Result(int count, boolean more, String markFailure) {

    public Result(int count, boolean more) {
      this(count, more, null);
    }
  }

  private final DlqMessages messages;
  private final Republisher republisher;
  private final Clock clock;
  private final Set<String> dlqTopics;
  private final int pageSize;

  public ReprocessDlqUseCase(
      DlqMessages messages,
      Republisher republisher,
      Clock clock,
      Set<String> dlqTopics,
      int pageSize) {
    this.messages = messages;
    this.republisher = republisher;
    this.clock = clock;
    this.dlqTopics = Set.copyOf(dlqTopics);
    this.pageSize = pageSize;
  }

  /**
   * Sends one page of pending messages back; the operator repeats the call while {@code more} is
   * true. The first message that cannot be sent keeps its reason, stops the run and is reported
   * with the count done so far. A send that succeeded but could not be marked is left pending (a
   * repeat sends it again and the inbox absorbs it) and stops the run, since the database is
   * unwell.
   */
  public Result reprocess(String dlqTopic, Long id) {
    if (!dlqTopics.contains(dlqTopic)) {
      throw new DlqNotFoundException("no such dead-letter topic: " + dlqTopic);
    }
    var pending = messages.pending(dlqTopic, id, pageSize + 1);
    if (id != null && pending.isEmpty()) {
      throw new DlqNotFoundException("no pending message " + id + " in " + dlqTopic);
    }
    var more = pending.size() > pageSize;
    var original = dlqTopic.substring(0, dlqTopic.length() - SUFFIX.length());
    var count = 0;
    for (var message : pending.subList(0, Math.min(pending.size(), pageSize))) {
      try {
        republisher.republish(original, message.key(), message.value());
      } catch (RuntimeException e) {
        var reason =
            e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        try {
          messages.markFailed(message.id(), "FAILED: " + reason);
        } catch (RuntimeException ignored) {
          // the reason is lost with the database; the interruption below is what matters
        }
        throw new ReprocessInterruptedException(count, reason);
      }
      count++;
      try {
        messages.markReprocessed(message.id(), clock.instant());
      } catch (RuntimeException e) {
        return new Result(count, true, e.toString());
      }
    }
    return new Result(count, more);
  }
}
