package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;

/** What the application properties hand to the Kafka clients: the wire format and the registry. */
class MessagingFormatConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class));

  private static Map<String, Object> producer(
      org.springframework.context.ApplicationContext context) {
    return context.getBean(ProducerFactory.class).getConfigurationProperties();
  }

  private static Map<String, Object> consumer(
      org.springframework.context.ApplicationContext context) {
    return context.getBean(ConsumerFactory.class).getConfigurationProperties();
  }

  @Test
  void theProducerAndEveryConsumerUseTheEnvelopeSerdes() {
    runner.run(
        context -> {
          assertThat(producer(context).get("value.serializer")).isEqualTo(EnvelopeSerializer.class);
          assertThat(consumer(context).get("value.deserializer"))
              .isEqualTo(EnvelopeDeserializer.class);
        });
  }

  @Test
  void theFormatIsJsonUnlessTheOperatorSwitchesIt() {
    runner.run(context -> assertThat(producer(context)).containsEntry(WireFormat.FORMAT, "json"));
  }

  @Test
  void avroModeReachesTheProducer() {
    runner
        .withPropertyValues("radar.messaging.format=avro")
        .run(context -> assertThat(producer(context)).containsEntry(WireFormat.FORMAT, "avro"));
  }

  @Test
  void theRegistryUrlReachesTheProducerAndTheConsumers() {
    runner
        .withPropertyValues("radar.messaging.registry-url=http://registry:8080/apis/registry/v3")
        .run(
            context -> {
              assertThat(producer(context))
                  .containsEntry("apicurio.registry.url", "http://registry:8080/apis/registry/v3");
              assertThat(consumer(context))
                  .containsEntry("apicurio.registry.url", "http://registry:8080/apis/registry/v3");
            });
  }

  @Test
  void theRegistryUrlDefaultsToTheComposeRegistryOnThisHost() {
    runner.run(
        context ->
            assertThat(consumer(context))
                .containsEntry("apicurio.registry.url", "http://localhost:8081/apis/registry/v3"));
  }

  @Test
  void theRegistryTimeoutReachesTheProducerAndTheConsumers() {
    runner
        .withPropertyValues("radar.messaging.registry-timeout-ms=2500")
        .run(
            context -> {
              assertThat(producer(context)).containsEntry(WireFormat.REGISTRY_TIMEOUT_MS, "2500");
              assertThat(consumer(context)).containsEntry(WireFormat.REGISTRY_TIMEOUT_MS, "2500");
            });
  }

  @Test
  void theRegistryBackoffReachesTheConsumers() {
    runner
        .withPropertyValues("radar.messaging.registry-backoff-ms=1500")
        .run(
            context ->
                assertThat(consumer(context))
                    .containsEntry(WireFormat.REGISTRY_BACKOFF_MS, "1500"));
  }
}
