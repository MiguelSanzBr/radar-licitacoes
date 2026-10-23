package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

@OutboxSpringBootTest
class OutboxSchedulerDisabledIT {

  @Autowired ApplicationContext context;
  @Autowired MeterRegistry registry;

  @Test
  void relayEnabledFalseRemovesTheScheduler() {
    assertThat(context.getBeansOfType(OutboxScheduler.class)).isEmpty();
  }

  @Test
  void aNonKafkaPublisherLeavesTheKafkaAdapterOut() {
    assertThat(context.getBeansOfType(KafkaEventPublisher.class)).isEmpty();
    assertThat(context.getBeansOfType(EventPublisher.class)).hasSize(1);
  }

  @Test
  void pollingModeIsTheDefaultAndKeepsTheRelayAndNoSlotGauge() {
    assertThat(context.getBean(OutboxProperties.class).mode())
        .isEqualTo(OutboxProperties.Mode.POLLING);
    assertThat(context.getBeansOfType(OutboxRelay.class)).hasSize(1);
    assertThat(registry.find("outbox_replication_slot_lag_bytes").gauge()).isNull();
  }
}
