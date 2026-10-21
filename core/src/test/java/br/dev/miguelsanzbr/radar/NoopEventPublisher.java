package br.dev.miguelsanzbr.radar;

import br.dev.miguelsanzbr.radar.outbox.EventPublisher;
import br.dev.miguelsanzbr.radar.outbox.OutboxMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The boot test has no broker yet; the relay only needs a publisher to exist. */
@Component
@Profile("noop")
class NoopEventPublisher implements EventPublisher {

  @Override
  public void publish(OutboxMessage message) {
    // nothing is pending in the boot test
  }
}
