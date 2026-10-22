package br.dev.miguelsanzbr.radar.ingest.application;

import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.domain.Procurement;
import java.time.Clock;
import java.util.List;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Emits {@code ProcurementClosed} for the published notices whose proposal window ended, one
 * transaction per page of candidates. A closed notice leaves the candidate query, so running again
 * (or from two instances) emits nothing more.
 */
public final class CloseExpiredUseCase {

  private final ProcurementRepository procurements;
  private final EventSink outbox;
  private final TransactionOperations transactions;
  private final Clock clock;
  private final int pageSize;

  public CloseExpiredUseCase(
      ProcurementRepository procurements,
      OutboxAppender outbox,
      TransactionOperations transactions,
      Clock clock,
      int pageSize) {
    this.procurements = procurements;
    this.outbox = outbox::append;
    this.transactions = transactions;
    this.clock = clock;
    this.pageSize = pageSize;
  }

  /** Returns how many notices were closed. */
  public int closeExpired() {
    var total = 0;
    while (true) {
      var candidates = procurements.findClosingCandidates(clock.instant(), pageSize);
      if (candidates.isEmpty()) {
        return total;
      }
      var closed = transactions.execute(status -> closePage(candidates));
      total += closed;
      // a candidate that does not close would come back forever
      if (closed == 0) {
        return total;
      }
    }
  }

  private int closePage(List<Procurement> candidates) {
    var closed = 0;
    for (var procurement : candidates) {
      try {
        var event = procurement.closeIfExpired(clock);
        if (event.isPresent()) {
          var saved = procurements.save(procurement);
          outbox.append("Procurement", saved.revision(), List.of(event.get()));
          closed++;
        }
      } catch (RuntimeException e) {
        throw new IllegalStateException("closing " + procurement.id() + " failed", e);
      }
    }
    return closed;
  }
}
