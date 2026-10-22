package br.dev.miguelsanzbr.radar.alerts.application;

import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.CLOCK;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.DIRECT;
import static br.dev.miguelsanzbr.radar.alerts.application.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyGuard.Stored;
import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore;
import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore.Recorded;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

  private static final UUID SUBSCRIBER = UUID.randomUUID();
  private static final byte[] HASH = {1, 2, 3};

  @Mock IdempotencyStore store;

  IdempotencyGuard guard;

  @BeforeEach
  void setUp() {
    guard = new IdempotencyGuard(store, DIRECT, CLOCK);
  }

  @Test
  void firstCallRunsTheActionAndRecordsItsResponse() {
    when(store.claim(SUBSCRIBER, "k", HASH, NOW, NOW.minus(Duration.ofHours(24)))).thenReturn(true);

    var result = guard.run(SUBSCRIBER, "k", HASH, () -> new Stored(201, "{}"));

    assertThat(result.replayed()).isFalse();
    assertThat(result.stored()).isEqualTo(new Stored(201, "{}"));
    verify(store).complete(SUBSCRIBER, "k", 201, "{}");
  }

  @Test
  void sameKeyAndSameBodyReplaysWithoutRunningTheAction() {
    when(store.claim(SUBSCRIBER, "k", HASH, NOW, NOW.minus(Duration.ofHours(24))))
        .thenReturn(false);
    when(store.find(SUBSCRIBER, "k", HASH)).thenReturn(Optional.of(new Recorded(true, 201, "{}")));
    var runs = new AtomicInteger();

    var result =
        guard.run(
            SUBSCRIBER,
            "k",
            HASH,
            () -> {
              runs.incrementAndGet();
              return new Stored(500, "boom");
            });

    assertThat(result.replayed()).isTrue();
    assertThat(result.stored()).isEqualTo(new Stored(201, "{}"));
    assertThat(runs).hasValue(0);
    verifyNoMoreInteractions(store);
  }

  @Test
  void sameKeyWithAnotherBodyIsAMismatch() {
    when(store.claim(SUBSCRIBER, "k", HASH, NOW, NOW.minus(Duration.ofHours(24))))
        .thenReturn(false);
    when(store.find(SUBSCRIBER, "k", HASH)).thenReturn(Optional.of(new Recorded(false, 201, "{}")));

    assertThatThrownBy(() -> guard.run(SUBSCRIBER, "k", HASH, () -> new Stored(201, "x")))
        .isInstanceOf(IdempotencyMismatchException.class);
  }
}
