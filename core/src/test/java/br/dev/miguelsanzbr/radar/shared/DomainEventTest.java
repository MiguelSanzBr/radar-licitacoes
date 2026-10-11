package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DomainEventTest {

  record SampleHappened(String aggregateId, Instant occurredAt) implements DomainEvent {}

  @Test
  void eventTypeDefaultsToTheSimpleClassName() {
    var event = new SampleHappened("a-1", Instant.EPOCH);

    assertThat(event.eventType()).isEqualTo("SampleHappened");
  }
}
