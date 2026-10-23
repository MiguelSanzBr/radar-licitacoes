package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rows per batch of the cents backfill. {@code radar.ingest.value-cents-backfill.enabled} (off by
 * default) and {@code .interval} are read by the scheduler itself.
 */
@ConfigurationProperties("radar.ingest.value-cents-backfill")
public record ValueCentsBackfillProperties(@DefaultValue("5000") int batchSize) {}
