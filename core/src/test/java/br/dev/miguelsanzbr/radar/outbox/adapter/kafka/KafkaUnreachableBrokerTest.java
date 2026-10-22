package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import br.dev.miguelsanzbr.radar.outbox.BrokerUnavailableException;
import br.dev.miguelsanzbr.radar.outbox.OutboxMessage;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** A real producer that cannot reach any broker: the failure must be broker-wide, whatever path. */
class KafkaUnreachableBrokerTest {

  @Test
  void aProducerThatCannotReachTheBrokerFailsWithBrokerUnavailable() {
    var factory =
        new DefaultKafkaProducerFactory<String, String>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                "127.0.0.1:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                300));
    try {
      var publisher =
          new KafkaEventPublisher(
              new KafkaTemplate<>(factory),
              new KafkaPublisherProperties(
                  Map.of("Procurement", "radar.procurement.v1"), Duration.ofSeconds(10), 1),
              new TraceparentExtractor(Tracer.NOOP, Propagator.NOOP));
      var message =
          new OutboxMessage(
              1,
              UUID.randomUUID(),
              "Procurement",
              "PNCP-1",
              "ProcurementUpdated",
              1,
              "{}",
              Map.of(),
              Instant.parse("2026-10-22T12:00:00Z"),
              0);

      assertThatThrownBy(() -> publisher.publish(message))
          .isInstanceOf(BrokerUnavailableException.class);
    } finally {
      factory.destroy();
    }
  }
}
