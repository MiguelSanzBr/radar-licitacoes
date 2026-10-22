package br.dev.miguelsanzbr.radar.ingest.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionCursorRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionCursor;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestConfig.class, JdbcIngestionCursorRepository.class})
class JdbcIngestionCursorRepositoryIT {

  private static final Instant WATERMARK = Instant.parse("2026-10-22T10:00:00.123Z");
  private static final Instant UPDATED = Instant.parse("2026-10-22T10:07:00Z");

  @Autowired IngestionCursorRepository repository;

  @Test
  void aModalityWithoutACursorHasNone() {
    assertThat(repository.find(99)).isEmpty();
  }

  @Test
  void aSavedCursorReloadsWithEveryField() {
    var cursor = new IngestionCursor(98, WATERMARK, Duration.ofMinutes(15), UPDATED);

    repository.save(cursor);

    assertThat(repository.find(98)).contains(cursor);
  }

  @Test
  void savingAgainReplacesTheRowOfTheModality() {
    repository.save(new IngestionCursor(97, WATERMARK, Duration.ofMinutes(15), UPDATED));
    var later = new IngestionCursor(97, WATERMARK.plusSeconds(60), Duration.ofMinutes(20), UPDATED);

    repository.save(later);

    assertThat(repository.find(97)).contains(later);
    assertThat(repository.find(96)).isEmpty();
  }
}
