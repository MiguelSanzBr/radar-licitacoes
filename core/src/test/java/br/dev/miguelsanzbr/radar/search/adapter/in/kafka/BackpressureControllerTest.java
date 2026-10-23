package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

class BackpressureControllerTest {

  /** A clock the test moves by hand. */
  private static final class TestClock extends Clock {
    private Instant now = Instant.parse("2026-10-23T12:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private final KafkaListenerEndpointRegistry registry = mock();
  private final MessageListenerContainer container = mock();
  private final TestClock clock = new TestClock();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final Timer timer = Timer.builder("search_projector_record_seconds").register(meters);
  private final BackpressureController controller =
      new BackpressureController(
          registry, clock, Duration.ofMillis(100), 3, Duration.ofSeconds(10), timer);

  private void withContainer() {
    when(registry.getListenerContainer(ProcurementEventsListener.ID)).thenReturn(container);
  }

  private void record(int times, long millis) {
    for (var i = 0; i < times; i++) {
      controller.record(Duration.ofMillis(millis));
    }
  }

  @Test
  void fastRecordsNeverPauseTheContainer() {
    withContainer();

    record(30, 10);

    assertThat(controller.paused()).isFalse();
    verify(container, never()).pause();
  }

  @Test
  void aWindowThatIsNotFullYetDecidesNothing() {
    withContainer();

    record(2, 5_000);

    assertThat(controller.paused()).isFalse();
    verify(container, never()).pause();
  }

  @Test
  void aSlowWindowPausesTheContainerOnce() {
    withContainer();

    record(3, 300);
    record(5, 300);

    assertThat(controller.paused()).isTrue();
    verify(container, times(1)).pause();
  }

  @Test
  void theMeanDecidesNotASingleSlowRecord() {
    withContainer();

    controller.record(Duration.ofMillis(290));
    record(2, 5);

    assertThat(controller.paused()).isFalse();
    verify(container, never()).pause();
  }

  @Test
  void aMeanExactlyAtTheLimitDoesNotPause() {
    withContainer();

    record(3, 100);

    assertThat(controller.paused()).isFalse();
  }

  @Test
  void theContainerResumesOnlyOnceThePauseHasPassed() {
    withContainer();
    record(3, 300);

    clock.advance(Duration.ofSeconds(9));
    controller.resumeIfDue();
    assertThat(controller.paused()).isTrue();
    verify(container, never()).resume();

    clock.advance(Duration.ofSeconds(1));
    controller.resumeIfDue();
    assertThat(controller.paused()).isFalse();
    verify(container, times(1)).resume();

    controller.resumeIfDue();
    verify(container, times(1)).resume();
  }

  @Test
  void afterTheResumeAFreshWindowIsNeededToPauseAgain() {
    withContainer();
    record(3, 300);
    clock.advance(Duration.ofSeconds(10));
    controller.resumeIfDue();

    record(2, 300);
    assertThat(controller.paused()).isFalse();

    record(1, 300);
    assertThat(controller.paused()).isTrue();
    verify(container, times(2)).pause();
  }

  @Test
  void withoutAProjectorContainerThereIsNothingToPauseOrResume() {
    when(registry.getListenerContainer(ProcurementEventsListener.ID)).thenReturn(null);

    record(3, 300);
    assertThat(controller.paused()).isTrue();

    clock.advance(Duration.ofSeconds(10));
    controller.resumeIfDue();
    assertThat(controller.paused()).isFalse();
  }

  @Test
  void theGaugeFollowsThePause() {
    withContainer();
    var configured =
        new SearchKafkaConfiguration()
            .backpressureController(
                registry, clock, meters, Duration.ofMillis(100), 3, Duration.ofSeconds(10));
    var gauge = meters.get("search_projector_paused").gauge();
    assertThat(gauge.value()).isZero();

    for (var i = 0; i < 3; i++) {
      configured.record(Duration.ofMillis(300));
    }

    assertThat(gauge.value()).isEqualTo(1.0);
    assertThat(meters.get("search_projector_record_seconds").timer().count()).isEqualTo(3);
    clock.advance(Duration.ofSeconds(10));
    configured.resumeIfDue();
    assertThat(gauge.value()).isZero();
  }

  @Test
  void aResumeThatFailsIsTriedAgainOnTheNextTick() {
    withContainer();
    record(3, 300);
    clock.advance(Duration.ofSeconds(10));
    doThrow(new IllegalStateException("broker")).doNothing().when(container).resume();

    assertThatThrownBy(controller::resumeIfDue).isInstanceOf(IllegalStateException.class);
    assertThat(controller.paused()).isTrue();

    controller.resumeIfDue();
    assertThat(controller.paused()).isFalse();
    verify(container, times(2)).resume();
  }

  @Test
  void everyRecordIsTimedInTheTimer() {
    withContainer();

    record(2, 40);

    assertThat(timer.count()).isEqualTo(2);
    assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(80.0);
  }

  @ParameterizedTest(name = "window {0}, slow-after {1} ms, pause-for {2} ms")
  @CsvSource({"0,100,1000", "-1,100,1000", "3,0,1000", "3,-5,1000", "3,100,0", "3,100,-1"})
  void aNonsenseConfigurationIsRefused(int window, long slowMillis, long pauseMillis) {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new BackpressureController(
                    registry,
                    clock,
                    Duration.ofMillis(slowMillis),
                    window,
                    Duration.ofMillis(pauseMillis),
                    timer));
  }

  @Test
  void theSmallestValidConfigurationIsAccepted() {
    var smallest =
        new BackpressureController(
            registry, clock, Duration.ofMillis(1), 1, Duration.ofMillis(1), timer);

    assertThat(smallest.paused()).isFalse();
  }
}
