package br.dev.miguelsanzbr.radar.ingest.application.port;

import br.dev.miguelsanzbr.radar.shared.DomainEvent;
import java.util.List;

/** Records events in the transactional outbox, in the caller's transaction. */
public interface OutboxAppender {

  /**
   * Appends the events in order.
   *
   * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is
   *     active: appending outside the transaction that saved the aggregate is a bug
   */
  void append(String aggregateType, long aggregateVersion, List<? extends DomainEvent> events);
}
