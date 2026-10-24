package br.dev.miguelsanzbr.radar.alerts.adapter.in.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase.Result;
import br.dev.miguelsanzbr.radar.alerts.application.port.ClusterLock;
import br.dev.miguelsanzbr.radar.ops.Flag;
import org.junit.jupiter.api.Test;

class DigestJobTest {

  private static final ClusterLock RAN =
      (name, work) -> {
        work.run();
        return true;
      };

  @Test
  void theDailyRunDelegatesToTheUseCase() {
    var useCase = mock(SendDigestUseCase.class);
    when(useCase.run()).thenReturn(new Result(2, 4, 1));

    new DigestJob(useCase, flag -> flag == Flag.ALERT_DIGEST, RAN).send();

    verify(useCase).run();
  }

  @Test
  void whileTheFlagIsOffNoDigestIsSent() {
    var useCase = mock(SendDigestUseCase.class);

    new DigestJob(useCase, flag -> false, RAN).send();

    verifyNoInteractions(useCase);
  }

  @Test
  void whenAnotherInstanceHoldsTheLockTheRunIsSkipped() {
    var useCase = mock(SendDigestUseCase.class);

    new DigestJob(useCase, flag -> true, (name, work) -> false).send();

    verifyNoInteractions(useCase);
  }

  @Test
  void theRunTakesTheClusterWideDigestLock() {
    var useCase = mock(SendDigestUseCase.class);
    when(useCase.run()).thenReturn(new Result(0, 0, 0));
    var names = new java.util.ArrayList<String>();

    new DigestJob(
            useCase,
            flag -> true,
            (name, work) -> {
              names.add(name);
              work.run();
              return true;
            })
        .send();

    org.assertj.core.api.Assertions.assertThat(names).containsExactly("radar.alerts.digest");
  }
}
