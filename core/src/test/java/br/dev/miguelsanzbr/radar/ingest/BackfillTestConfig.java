package br.dev.miguelsanzbr.radar.ingest;

import br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.JpaIngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ingest.domain.RunStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
public class BackfillTestConfig {

  /** Makes saving the run fail once its cursor reaches a page, to break a page transaction. */
  static final class FlakyRunRepository implements IngestionRunRepository {
    /** Cursor value whose save fails; 0 means never. */
    final AtomicInteger failAtCursor = new AtomicInteger();

    private final IngestionRunRepository delegate;

    FlakyRunRepository(IngestionRunRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public IngestionRun save(IngestionRun run) {
      if (failAtCursor.get() > 0
          && run.cursorPage() == failAtCursor.get()
          && run.status() != RunStatus.FAILED) {
        throw new IllegalStateException(
            "injected failure saving the run at page " + run.cursorPage());
      }
      return delegate.save(run);
    }

    @Override
    public Optional<IngestionRun> findResumable(
        IngestionKind kind, int modalityId, Instant windowStart, Instant windowEnd) {
      return delegate.findResumable(kind, modalityId, windowStart, windowEnd);
    }

    @Override
    public boolean existsDone(
        IngestionKind kind, int modalityId, Instant windowStart, Instant windowEnd) {
      return delegate.existsDone(kind, modalityId, windowStart, windowEnd);
    }
  }

  @Bean
  @Primary
  BackfillTestSupport.MutableClock testClock() {
    return new BackfillTestSupport.MutableClock();
  }

  @Bean
  @Primary
  FlakyRunRepository flakyRunRepository(JpaIngestionRunRepository real) {
    return new FlakyRunRepository(real);
  }

  @Bean
  DynamicPropertyRegistrar pncpBaseUrl() {
    return registry -> registry.add("radar.pncp.base-url", BackfillTestSupport.PNCP::baseUrl);
  }
}
