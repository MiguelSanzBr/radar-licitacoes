package br.dev.miguelsanzbr.radar.alerts.application.port;

import java.util.function.Supplier;

/** Transaction boundary; nested calls join the outer transaction. */
public interface UnitOfWork {

  <T> T inTransaction(Supplier<T> work);

  default void run(Runnable work) {
    inTransaction(
        () -> {
          work.run();
          return null;
        });
  }
}
