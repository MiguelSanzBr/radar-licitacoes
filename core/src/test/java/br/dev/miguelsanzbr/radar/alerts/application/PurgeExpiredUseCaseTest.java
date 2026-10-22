package br.dev.miguelsanzbr.radar.alerts.application;

import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.CLOCK;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.DIRECT;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurgeExpiredUseCaseTest {

  @Mock SubscriberRepository subscribers;
  @Mock IdempotencyStore idempotency;

  @Test
  void purgesKeysOlderThanADayAndSubscribersUnverifiedForAWeek() {
    when(idempotency.purgeBefore(NOW.minus(Duration.ofHours(24)))).thenReturn(3);
    when(subscribers.purgeUnverifiedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(2);

    var purged = new PurgeExpiredUseCase(subscribers, idempotency, DIRECT, CLOCK).run();

    assertThat(purged.idempotencyKeys()).isEqualTo(3);
    assertThat(purged.subscribers()).isEqualTo(2);
  }
}
