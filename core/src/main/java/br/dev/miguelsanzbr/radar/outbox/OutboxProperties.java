package br.dev.miguelsanzbr.radar.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Relay and cleanup tuning. {@code radar.outbox.relay-enabled} and {@code
 * radar.outbox.poll-interval} are read by the scheduler directly.
 */
@ConfigurationProperties("radar.outbox")
public record OutboxProperties(
    @DefaultValue("100") int batchSize,
    @DefaultValue("10") int maxAttempts,
    @DefaultValue("7d") Duration retention,
    @DefaultValue("10000") int cleanupBatchSize) {}
