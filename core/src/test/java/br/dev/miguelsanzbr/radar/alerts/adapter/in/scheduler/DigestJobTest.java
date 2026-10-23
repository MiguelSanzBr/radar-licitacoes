package br.dev.miguelsanzbr.radar.alerts.adapter.in.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase.Result;
import org.junit.jupiter.api.Test;

class DigestJobTest {

  @Test
  void theDailyRunDelegatesToTheUseCase() {
    var useCase = mock(SendDigestUseCase.class);
    when(useCase.run()).thenReturn(new Result(2, 4, 1));

    new DigestJob(useCase).send();

    verify(useCase).run();
  }
}
