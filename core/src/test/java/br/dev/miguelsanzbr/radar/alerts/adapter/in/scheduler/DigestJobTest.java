package br.dev.miguelsanzbr.radar.alerts.adapter.in.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase.Result;
import br.dev.miguelsanzbr.radar.ops.Flag;
import org.junit.jupiter.api.Test;

class DigestJobTest {

  @Test
  void theDailyRunDelegatesToTheUseCase() {
    var useCase = mock(SendDigestUseCase.class);
    when(useCase.run()).thenReturn(new Result(2, 4, 1));

    new DigestJob(useCase, flag -> flag == Flag.ALERT_DIGEST).send();

    verify(useCase).run();
  }

  @Test
  void whileTheFlagIsOffNoDigestIsSent() {
    var useCase = mock(SendDigestUseCase.class);

    new DigestJob(useCase, flag -> false).send();

    verifyNoInteractions(useCase);
  }
}
