package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How many months after the current one the job keeps. {@code radar.ingest.partitions.enabled} and
 * {@code .cron} are read by the scheduler itself.
 */
@ConfigurationProperties("radar.ingest.partitions")
public record PartitionProperties(@DefaultValue("3") int monthsAhead) {}
