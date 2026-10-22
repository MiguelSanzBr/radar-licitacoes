package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaOutboxConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(KafkaOutboxConfiguration.class)
          .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
          .withBean(
              TraceparentExtractor.class,
              () -> new TraceparentExtractor(Tracer.NOOP, Propagator.NOOP));

  @Test
  void topicNamesFollowTheConfiguredMapWithDlqVariants() {
    runner
        .withPropertyValues(
            "radar.outbox.topics.Procurement=custom.procurement",
            "radar.outbox.topics.Alert=custom.alert")
        .run(
            context ->
                assertThat(context.getBeansOfType(NewTopic.class).values())
                    .extracting(NewTopic::name)
                    .containsExactlyInAnyOrder(
                        "custom.procurement",
                        "custom.procurement.dlq",
                        "custom.alert",
                        "custom.alert.dlq"));
  }

  @Test
  void deadLetterCountersStartAtZeroOnePerDlqTopic() {
    runner
        .withPropertyValues(
            "radar.outbox.topics.Procurement=custom.procurement",
            "radar.outbox.topics.Alert=custom.alert")
        .run(
            context -> {
              var registry = new SimpleMeterRegistry();
              context.getBean(MeterBinder.class).bindTo(registry);

              assertThat(registry.get("dlq_messages_total").counters())
                  .extracting(counter -> counter.getId().getTag("topic"))
                  .containsExactlyInAnyOrder("custom.procurement.dlq", "custom.alert.dlq");
              assertThat(registry.get("dlq_messages_total").counters())
                  .allSatisfy(counter -> assertThat(counter.count()).isZero());
            });
  }

  @Test
  void replicationFactorComesFromTheConfiguration() {
    runner
        .withPropertyValues(
            "radar.outbox.topics.Procurement=p",
            "radar.outbox.topics.Alert=a",
            "radar.outbox.replication-factor=3")
        .run(
            context ->
                assertThat(context.getBeansOfType(NewTopic.class).values())
                    .extracting(NewTopic::replicationFactor)
                    .containsOnly((short) 3));
  }

  @Test
  void aMissingTopicMappingFailsTheStartupWithItsKey() {
    runner
        .withPropertyValues("radar.outbox.topics.Procurement=p")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("radar.outbox.topics.Alert"));
  }

  @Test
  void anotherPublisherLeavesTheKafkaAdapterOut() {
    runner
        .withPropertyValues("radar.outbox.publisher=inmemory")
        .run(context -> assertThat(context).doesNotHaveBean(KafkaEventPublisher.class));
  }
}
