package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.ingest.application.CloseExpiredUseCase;
import br.dev.miguelsanzbr.radar.ops.Flag;
import org.junit.jupiter.api.Test;

class ScheduledCloseExpiredTest {

  private final CloseExpiredUseCase useCase = mock(CloseExpiredUseCase.class);
  private boolean on = true;
  private final ScheduledCloseExpired scheduled =
      new ScheduledCloseExpired(useCase, flag -> flag == Flag.CLOSE_EXPIRED && on);

  @Test
  void runsTheUseCase() {
    when(useCase.closeExpired()).thenReturn(3);

    scheduled.run();

    verify(useCase).closeExpired();
  }

  @Test
  void whileTheFlagIsOffNothingIsClosed() {
    on = false;

    scheduled.run();

    verifyNoInteractions(useCase);
  }

  @Test
  void aFailureIsLoggedAndTheNextRunStillHappens() {
    when(useCase.closeExpired()).thenThrow(new IllegalStateException("db down"));

    assertThatCode(scheduled::run).doesNotThrowAnyException();
  }
}
