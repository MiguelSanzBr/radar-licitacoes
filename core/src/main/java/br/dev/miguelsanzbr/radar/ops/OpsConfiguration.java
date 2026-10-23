package br.dev.miguelsanzbr.radar.ops;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
class OpsConfiguration {

  @Bean
  DeadLetters deadLetters(
      KafkaTemplate<String, String> template, MeterRegistry registry, Clock clock) {
    return new DeadLetters(template, registry, clock);
  }
}
