package br.dev.miguelsanzbr.radar.outbox;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code radar.messaging}: the wire format of the events and the schema registry behind Avro. The
 * Kafka serdes read the same keys from the client properties; binding them here makes a format that
 * is neither {@code json} nor {@code avro} (in any case) fail the startup, instead of the first
 * send.
 */
@ConfigurationProperties("radar.messaging")
@Validated
public record MessagingProperties(
    @DefaultValue("json") Format format,
    @DefaultValue("http://localhost:8081/apis/registry/v3") String registryUrl,
    @DefaultValue("10000") @Min(1) int registryTimeoutMs,
    @DefaultValue("5000") @Min(0) int registryBackoffMs) {

  public enum Format {
    JSON,
    AVRO
  }
}
