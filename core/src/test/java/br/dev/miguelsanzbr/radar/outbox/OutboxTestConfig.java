package br.dev.miguelsanzbr.radar.outbox;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class OutboxTestConfig {

  @Bean
  InMemoryEventPublisher inMemoryEventPublisher() {
    return new InMemoryEventPublisher();
  }
}
