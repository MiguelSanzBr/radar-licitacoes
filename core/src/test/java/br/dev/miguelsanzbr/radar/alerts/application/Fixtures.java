package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.domain.Subscriber;
import br.dev.miguelsanzbr.radar.shared.Email;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Shared deterministic collaborators for the use case tests. */
final class Fixtures {

  static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final RandomGenerator RANDOM = RandomGenerator.of("L64X128MixRandom");
  static final UuidV7 IDS = new UuidV7(CLOCK, RANDOM);

  /** Runs the work inline, like a transaction that always commits. */
  static final UnitOfWork DIRECT =
      new UnitOfWork() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
          return work.get();
        }
      };

  static Subscriber subscriber() {
    return Subscriber.register(new Email("fulano@example.com"), NOW, CLOCK, IDS);
  }

  private Fixtures() {}
}
