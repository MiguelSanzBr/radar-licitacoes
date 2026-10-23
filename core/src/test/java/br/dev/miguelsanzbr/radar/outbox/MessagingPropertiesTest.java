package br.dev.miguelsanzbr.radar.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MessagingPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(OutboxConfiguration.class);

  @Test
  void theFormatDefaultsToJson() {
    runner.run(
        context ->
            assertThat(context.getBean(MessagingProperties.class).format())
                .isEqualTo(MessagingProperties.Format.JSON));
  }

  @Test
  void theFormatIsReadIgnoringCase() {
    runner
        .withPropertyValues("radar.messaging.format=Avro")
        .run(
            context ->
                assertThat(context.getBean(MessagingProperties.class).format())
                    .isEqualTo(MessagingProperties.Format.AVRO));
  }

  @Test
  void aFormatThatIsNeitherJsonNorAvroFailsTheStartup() {
    runner
        .withPropertyValues("radar.messaging.format=protobuf")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("radar.messaging.format"));
  }

  @Test
  void theRegistryUrlAndTheTimeoutHaveDefaults() {
    runner.run(
        context -> {
          var properties = context.getBean(MessagingProperties.class);
          assertThat(properties.registryUrl()).isEqualTo("http://localhost:8081/apis/registry/v3");
          assertThat(properties.registryTimeoutMs()).isEqualTo(10_000);
        });
  }

  @Test
  void theRegistryTimeoutMustBeAtLeastOneMillisecond() {
    runner
        .withPropertyValues("radar.messaging.registry-timeout-ms=0")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("registryTimeoutMs"));
  }

  @Test
  void theRegistryBackoffMayBeZeroButNotNegative() {
    runner
        .withPropertyValues("radar.messaging.registry-backoff-ms=0")
        .run(context -> assertThat(context).hasNotFailed());
    runner
        .withPropertyValues("radar.messaging.registry-backoff-ms=-1")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("registryBackoffMs"));
  }

  @Test
  void theRegistryBackoffDefaultsToFiveSeconds() {
    runner.run(
        context ->
            assertThat(context.getBean(MessagingProperties.class).registryBackoffMs())
                .isEqualTo(5_000));
  }
}
