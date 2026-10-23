package br.dev.miguelsanzbr.radar.outbox;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class OutboxSchedulerTest {

  private final OutboxRelay relay = mock(OutboxRelay.class);
  private final OutboxCleanup cleanup = mock(OutboxCleanup.class);
  private final OutboxProperties properties =
      new OutboxProperties(
          100, 10, Duration.ofDays(7), 10_000, OutboxProperties.Mode.POLLING, "radar_outbox");
  private final OutboxScheduler scheduler =
      new OutboxScheduler(providerOf(relay), cleanup, properties);

  private static ObjectProvider<OutboxRelay> providerOf(OutboxRelay... relays) {
    var beans = new StaticListableBeanFactory();
    for (var relay : relays) {
      beans.addBean("relay", relay);
    }
    return beans.getBeanProvider(OutboxRelay.class);
  }

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
  void relayDoesNothingWhenThereIsNoRelayBecauseCdcPublishes() {
    var cdc = new OutboxScheduler(providerOf(), cleanup, properties);

    cdc.relay();

    verify(relay, times(0)).relayOnce();
  }

  @Test
  void cleanupDelegates() {
    when(cleanup.cleanOnce()).thenReturn(3);

    scheduler.cleanup();

    verify(cleanup).cleanOnce();
  }
}
