package br.dev.miguelsanzbr.radar.architecture.fixture.application;

import br.dev.miguelsanzbr.radar.architecture.fixture.domain.PureDomain;
import br.dev.miguelsanzbr.radar.architecture.fixture.shared.SharedKernelType;
import java.util.List;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Complies with the application allow-list: domain, the shared kernel, java.* and
 * TransactionOperations.
 */
public class UsesOnlyAllowedTypes {

  private final TransactionOperations tx;
  SharedKernelType kernel;

  UsesOnlyAllowedTypes(TransactionOperations tx) {
    this.tx = tx;
  }

  PureDomain run() {
    return tx.execute(status -> new PureDomain(List.of("a")));
  }
}
