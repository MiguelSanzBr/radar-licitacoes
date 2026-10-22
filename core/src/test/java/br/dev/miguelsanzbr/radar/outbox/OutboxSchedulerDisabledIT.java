package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

@OutboxSpringBootTest
class OutboxSchedulerDisabledIT {

  @Autowired ApplicationContext context;

  @Test
  void relayEnabledFalseRemovesTheScheduler() {
    assertThat(context.getBeansOfType(OutboxScheduler.class)).isEmpty();
  }

  @Test
  void aNonKafkaPublisherLeavesTheKafkaAdapterOut() {
    assertThat(context.getBeansOfType(KafkaEventPublisher.class)).isEmpty();
    assertThat(context.getBeansOfType(EventPublisher.class)).hasSize(1);
  }
}
