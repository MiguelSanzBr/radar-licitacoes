package br.dev.miguelsanzbr.radar.alerts.adapter.out.jdbc;

import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SpringUnitOfWork implements UnitOfWork {

  private final TransactionTemplate template;

  SpringUnitOfWork(PlatformTransactionManager transactionManager) {
    this.template = new TransactionTemplate(transactionManager);
  }

  @Override
  public <T> T inTransaction(Supplier<T> work) {
    return template.execute(status -> work.get());
  }
}
