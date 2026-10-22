package br.dev.miguelsanzbr.radar.alerts.adapter.in.scheduler;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.PurgeExpiredUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.PurgeExpiredUseCase.Purged;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PurgeJobTest {

  @Test
  void dailyRunDelegatesToTheUseCase() {
    var useCase = Mockito.mock(PurgeExpiredUseCase.class);
    when(useCase.run()).thenReturn(new Purged(1, 2));

    new PurgeJob(useCase).purge();

    verify(useCase).run();
  }
}
