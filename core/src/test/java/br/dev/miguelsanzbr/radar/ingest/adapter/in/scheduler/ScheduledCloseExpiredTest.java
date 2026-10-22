package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ingest.application.CloseExpiredUseCase;
import org.junit.jupiter.api.Test;

class ScheduledCloseExpiredTest {

  private final CloseExpiredUseCase useCase = mock(CloseExpiredUseCase.class);
  private final ScheduledCloseExpired scheduled = new ScheduledCloseExpired(useCase);

  @Test
  void runsTheUseCase() {
    when(useCase.closeExpired()).thenReturn(3);

    scheduled.run();

    verify(useCase).closeExpired();
  }

  @Test
  void aFailureIsLoggedAndTheNextRunStillHappens() {
    when(useCase.closeExpired()).thenThrow(new IllegalStateException("db down"));

    assertThatCode(scheduled::run).doesNotThrowAnyException();
  }
}
