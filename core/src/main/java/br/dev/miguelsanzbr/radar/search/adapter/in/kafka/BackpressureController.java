package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Pauses the projector container when the projection is slow, and resumes it after a cool-down.
 * Slow is the mean time of a full window of records above the limit: the window is tumbling and
 * starts empty after each pause, so one slow record or one poll never decides, and a container that
 * just resumed gets a fresh chance. Pausing does not shrink the backlog (it grows while paused); it
 * gives a database that is struggling a quiet period, and Kafka keeps the group alive because a
 * paused consumer still polls.
 */
final class BackpressureController {

  private final KafkaListenerEndpointRegistry registry;
  private final Clock clock;
  private final Duration slowAfter;
  private final int window;
  private final Duration pauseFor;
  private final Timer recordTimer;

  private Duration total = Duration.ZERO;
  private int seen;
  private Instant pausedUntil;

  BackpressureController(
      KafkaListenerEndpointRegistry registry,
      Clock clock,
      Duration slowAfter,
      int window,
      Duration pauseFor,
      Timer recordTimer) {
    if (window < 1) {
      throw new IllegalArgumentException("backpressure window must be at least 1: " + window);
    }
    if (slowAfter.compareTo(Duration.ZERO) <= 0) {
      throw new IllegalArgumentException("backpressure slow-after must be positive: " + slowAfter);
    }
    if (pauseFor.compareTo(Duration.ZERO) <= 0) {
      throw new IllegalArgumentException("backpressure pause-for must be positive: " + pauseFor);
    }
    this.registry = registry;
    this.clock = clock;
    this.slowAfter = slowAfter;
    this.window = window;
    this.pauseFor = pauseFor;
    this.recordTimer = recordTimer;
  }

  /** Called by a consumer thread after each record it projected and acknowledged. */
  synchronized void record(Duration took) {
    recordTimer.record(took);
    if (pausedUntil != null) {
      // a record that was in flight when the pause was requested
      return;
    }
    total = total.plus(took);
    if (++seen < window) {
      return;
    }
    var mean = total.dividedBy(seen);
    total = Duration.ZERO;
    seen = 0;
    if (mean.compareTo(slowAfter) > 0) {
      pausedUntil = clock.instant().plus(pauseFor);
      container().ifPresent(MessageListenerContainer::pause);
    }
  }

  @Scheduled(fixedDelayString = "${radar.search.projector.backpressure.check-interval:1s}")
  synchronized void resumeIfDue() {
    if (pausedUntil != null && !clock.instant().isBefore(pausedUntil)) {
      // why: the flag clears only after the resume worked, so a resume that throws is tried again
      container().ifPresent(MessageListenerContainer::resume);
      pausedUntil = null;
    }
  }

  synchronized boolean paused() {
    return pausedUntil != null;
  }

  private Optional<MessageListenerContainer> container() {
    return Optional.ofNullable(registry.getListenerContainer(ProcurementEventsListener.ID));
  }
}
