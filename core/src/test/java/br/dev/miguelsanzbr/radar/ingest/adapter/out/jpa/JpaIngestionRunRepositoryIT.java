package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ingest.domain.RunStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

@PostgresJpaTest
@Import(JpaIngestionRunRepository.class)
class JpaIngestionRunRepositoryIT {

  private static final Instant START = Instant.parse("2026-10-20T03:00:00Z");
  private static final Instant END = Instant.parse("2026-10-21T03:00:00Z");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC);

  @Autowired IngestionRunRepository repository;
  @Autowired TestEntityManager entityManager;

  private IngestionRun run(IngestionKind kind, int modality, Instant start, Instant end) {
    return IngestionRun.start(kind, modality, start, end, CLOCK);
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  @Test
  void aSavedRunGetsAnIdAndReloadsWithEveryField() {
    var run = run(IngestionKind.BACKFILL, 6, START, END);
    run.pageProcessed(1, 50, 30, 15, 5);
    run.fail("boom", CLOCK);

    var saved = repository.save(run);
    flushAndClear();

    assertThat(saved.id()).isNotNull();
    var reloaded = repository.findResumable(IngestionKind.BACKFILL, 6, START, END).orElseThrow();
    assertThat(reloaded).usingRecursiveComparison().isEqualTo(saved);
    assertThat(reloaded.status()).isEqualTo(RunStatus.FAILED);
    assertThat(reloaded.cursorPage()).isEqualTo(1);
    assertThat(reloaded.fetched()).isEqualTo(50);
    assertThat(reloaded.discovered()).isEqualTo(30);
    assertThat(reloaded.updated()).isEqualTo(15);
    assertThat(reloaded.unchanged()).isEqualTo(5);
    assertThat(reloaded.error()).isEqualTo("boom");
    assertThat(reloaded.finishedAt()).isEqualTo(CLOCK.instant());
    assertThat(reloaded.startedAt()).isEqualTo(CLOCK.instant());
    assertThat(reloaded.windowStart()).isEqualTo(START);
    assertThat(reloaded.windowEnd()).isEqualTo(END);
  }

  @Test
  void savingAgainUpdatesTheSameRow() {
    var saved = repository.save(run(IngestionKind.BACKFILL, 6, START, END));
    saved.pageProcessed(1, 10, 10, 0, 0);
    saved.finish(CLOCK);

    var updated = repository.save(saved);
    flushAndClear();

    assertThat(updated.id()).isEqualTo(saved.id());
    assertThat(repository.findResumable(IngestionKind.BACKFILL, 6, START, END)).isEmpty();
    assertThat(
            entityManager
                .getEntityManager()
                .createQuery("select count(r) from IngestionRunEntity r", Long.class)
                .getSingleResult())
        .isEqualTo(1L);
  }

  @Test
  void onlyUnfinishedRunsOfTheSameKindModalityAndWindowAreResumable() {
    repository.save(run(IngestionKind.BACKFILL, 6, START, END));
    var done = run(IngestionKind.BACKFILL, 6, END, END.plusSeconds(86_400));
    done.finish(CLOCK);
    repository.save(done);
    flushAndClear();

    assertThat(repository.findResumable(IngestionKind.BACKFILL, 6, START, END)).isPresent();
    assertThat(repository.findResumable(IngestionKind.INCREMENTAL, 6, START, END)).isEmpty();
    assertThat(repository.findResumable(IngestionKind.BACKFILL, 12, START, END)).isEmpty();
    assertThat(repository.findResumable(IngestionKind.BACKFILL, 6, START.plusSeconds(1), END))
        .isEmpty();
    assertThat(repository.findResumable(IngestionKind.BACKFILL, 6, START, END.plusSeconds(1)))
        .isEmpty();
    assertThat(repository.findResumable(IngestionKind.BACKFILL, 6, END, END.plusSeconds(86_400)))
        .isEmpty();
  }

  @Test
  void aWindowHasAtMostOneUnfinishedRunButManyFinishedOnes() {
    var first = run(IngestionKind.BACKFILL, 6, START, END);
    first.finish(CLOCK);
    repository.save(first);
    repository.save(run(IngestionKind.BACKFILL, 6, START, END));
    flushAndClear();

    assertThatThrownBy(
            () -> {
              repository.save(run(IngestionKind.BACKFILL, 6, START, END));
              entityManager.flush();
            })
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theVersionAdvancesOnEverySaveAndAStaleCopyIsRejected() {
    var first = repository.save(run(IngestionKind.BACKFILL, 6, START, END));
    assertThat(first.version()).isZero();
    first.pageProcessed(1, 5, 5, 0, 0);
    var second = repository.save(first);
    assertThat(second.version()).isEqualTo(1);
    flushAndClear();

    // `first` still carries version 0: another writer got there before it
    first.pageProcessed(2, 5, 5, 0, 0);
    assertThatThrownBy(() -> repository.save(first))
        .isInstanceOf(OptimisticLockingFailureException.class);
    second.pageProcessed(2, 5, 5, 0, 0);
    var third = repository.save(second);
    assertThat(third.version()).isEqualTo(2);
  }

  @Test
  void existsDoneSeesOnlyFinishedRunsOfTheSameWindow() {
    var open = run(IngestionKind.BACKFILL, 6, START, END);
    repository.save(open);
    assertThat(repository.existsDone(IngestionKind.BACKFILL, 6, START, END)).isFalse();
    var done = run(IngestionKind.BACKFILL, 6, END, END.plusSeconds(86_400));
    done.finish(CLOCK);
    repository.save(done);
    flushAndClear();

    assertThat(repository.existsDone(IngestionKind.BACKFILL, 6, END, END.plusSeconds(86_400)))
        .isTrue();
    assertThat(repository.existsDone(IngestionKind.INCREMENTAL, 6, END, END.plusSeconds(86_400)))
        .isFalse();
    assertThat(repository.existsDone(IngestionKind.BACKFILL, 12, END, END.plusSeconds(86_400)))
        .isFalse();
    assertThat(repository.existsDone(IngestionKind.BACKFILL, 6, START, END)).isFalse();
  }

  @Test
  void savingARunThatWasNeverStoredIsRejected() {
    var ghost =
        IngestionRun.restore(
            987_654L,
            0,
            IngestionKind.BACKFILL,
            6,
            START,
            END,
            0,
            RunStatus.RUNNING,
            0,
            0,
            0,
            0,
            CLOCK.instant(),
            null,
            null);

    assertThatThrownBy(() -> repository.save(ghost))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("987654");
  }
}
