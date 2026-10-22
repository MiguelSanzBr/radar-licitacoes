package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import br.dev.miguelsanzbr.radar.SharedKafka;
import java.util.List;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Points the context at the shared broker without making the container a Spring bean. */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestConfig {

  @Bean
  KafkaConnectionDetails kafkaConnectionDetails() {
    return () -> List.of(SharedKafka.INSTANCE.getBootstrapServers());
  }
}
