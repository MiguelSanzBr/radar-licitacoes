package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

// why: the live 50 ms relay of this context must stop with the class, not leak into later ITs
@DirtiesContext
@SpringBootTest(properties = {"radar.outbox.poll-interval=50ms", "radar.outbox.publisher=inmemory"})
@Import({PostgresTestConfig.class, OutboxTestConfig.class})
class OutboxSchedulerIT {

  @Autowired JdbcTemplate jdbc;
  @Autowired InMemoryEventPublisher publisher;

  @Autowired(required = false)
  OutboxScheduler scheduler;

  @Test
  void enabledByDefaultAndPublishesWithoutBeingCalled() {
    jdbc.update("DELETE FROM outbox.outbox_event");
    var id = OutboxRows.insert(jdbc, "A", "ProcurementUpdated");

    await().atMost(Duration.ofSeconds(10)).until(() -> publisher.publishedIds().contains(id));

    assertThat(scheduler).isNotNull();
  }
}
