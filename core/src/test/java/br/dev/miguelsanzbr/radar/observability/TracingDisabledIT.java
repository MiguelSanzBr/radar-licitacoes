package br.dev.miguelsanzbr.radar.observability;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import br.dev.miguelsanzbr.radar.outbox.OutboxTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(
    properties = {
      "management.tracing.enabled=false",
      "radar.outbox.relay-enabled=false",
      "radar.outbox.publisher=inmemory"
    })
@Import({PostgresTestConfig.class, OutboxTestConfig.class})
class TracingDisabledIT {

  @Autowired TraceparentInjector injector;

  @Test
  void theAppStartsWithTracingOffAndWritesNoTraceparent() {
    assertThat(injector.current()).isNull();
  }
}
