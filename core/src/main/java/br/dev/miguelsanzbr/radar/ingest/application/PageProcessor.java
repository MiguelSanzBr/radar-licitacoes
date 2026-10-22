package br.dev.miguelsanzbr.radar.ingest.application;

import br.dev.miguelsanzbr.radar.ingest.application.port.IngestMetrics;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpPage;
import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import br.dev.miguelsanzbr.radar.shared.DomainEvent;
import java.time.Clock;
import java.util.List;
import org.springframework.transaction.support.TransactionOperations;

/**
 * What backfill and incremental sync share: one PNCP page becomes notices, outbox events and run
 * progress in a single transaction, and a failed run is marked FAILED.
 */
public final class PageProcessor {

  private record Processed(IngestionRun run, int discovered, int updated, int unchanged) {}

  private final ProcurementRepository procurements;
  private final IngestionRunRepository runs;
  private final EventSink outbox;
  private final TransactionOperations transactions;
  private final IngestMetrics metrics;
  private final Clock clock;

  public PageProcessor(
      ProcurementRepository procurements,
      IngestionRunRepository runs,
      OutboxAppender outbox,
      TransactionOperations transactions,
      IngestMetrics metrics,
      Clock clock) {
    this.procurements = procurements;
    this.runs = runs;
    this.outbox = outbox::append;
    this.transactions = transactions;
    this.metrics = metrics;
    this.clock = clock;
  }

  /**
   * One transaction: the page's notices, their events and the run (cursor, and DONE on the last
   * page, so a finished page is never left with an unfinished run). The page is counted in the
   * metrics only after it committed. Returns the saved run.
   */
  IngestionRun process(IngestionRun run, int page, PncpPage result) {
    var processed =
        transactions.execute(
            status -> {
              var discovered = 0;
              var updated = 0;
              for (var snapshot : result.items()) {
                // shortcut: one lookup per notice; findAllById per page would cut it to two queries
                var existing = procurements.findById(snapshot.id());
                Procurement procurement;
                List<? extends DomainEvent> events;
                if (existing.isEmpty()) {
                  var discovery = Procurement.discover(snapshot, clock);
                  procurement = discovery.procurement();
                  events = discovery.events();
                  discovered++;
                } else {
                  procurement = existing.get();
                  events = procurement.applySnapshot(snapshot, clock);
                  if (!events.isEmpty()) {
                    updated++;
                  }
                }
                var saved = procurements.save(procurement);
                if (!events.isEmpty()) {
                  outbox.append("Procurement", saved.revision(), events);
                }
              }
              var total = result.items().size();
              var unchanged = total - discovered - updated;
              run.pageProcessed(page, total, discovered, updated, unchanged);
              if (result.isLast()) {
                run.finish(clock);
              }
              return new Processed(runs.save(run), discovered, updated, unchanged);
            });
    metrics.itemsProcessed(
        run.kind(), processed.discovered(), processed.updated(), processed.unchanged());
    return processed.run();
  }

  /** A window the PNCP answered with no data at all: nothing to process, the run is done. */
  IngestionRun finishEmpty(IngestionRun run) {
    run.finish(clock);
    return runs.save(run);
  }

  /**
   * Marks the stored run FAILED; a failure while doing so is added to {@code cause} as suppressed.
   * The stored state is reloaded because a rolled back page may have moved the cursor of the
   * in-memory run past what was committed.
   */
  void fail(IngestionRun run, RuntimeException cause) {
    try {
      var stored =
          runs.findResumable(run.kind(), run.modalityId(), run.windowStart(), run.windowEnd())
              .orElseThrow();
      stored.fail(cause.getClass().getSimpleName() + ": " + cause.getMessage(), clock);
      runs.save(stored);
    } catch (RuntimeException failure) {
      cause.addSuppressed(failure);
    }
  }
}
