package br.dev.miguelsanzbr.radar.outbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxSchedulerTest {

  private final OutboxRelay relay = mock(OutboxRelay.class);
  private final OutboxCleanup cleanup = mock(OutboxCleanup.class);
  private final OutboxScheduler scheduler =
      new OutboxScheduler(
          relay, cleanup, new OutboxProperties(100, 10, Duration.ofDays(7), 10_000));

  @Test
  void relayKeepsDrainingWhileBatchesComeBackFull() {
    when(relay.relayOnce()).thenReturn(100, 100, 7);

    scheduler.relay();

    verify(relay, times(3)).relayOnce();
  }

  @Test
  void relayRunsOnceWhenNothingIsPending() {
    when(relay.relayOnce()).thenReturn(0);

    scheduler.relay();

    verify(relay, times(1)).relayOnce();
  }

  @Test
  void cleanupDelegates() {
    when(cleanup.cleanOnce()).thenReturn(3);

    scheduler.cleanup();

    verify(cleanup).cleanOnce();
  }
}
