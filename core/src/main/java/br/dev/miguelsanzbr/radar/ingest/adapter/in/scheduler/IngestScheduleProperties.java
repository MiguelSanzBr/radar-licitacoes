package br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The modalities to keep in sync and the incremental window. {@code
 * radar.ingest.incremental.interval} and {@code radar.ingest.close-expired.interval} are read by
 * the schedulers themselves; the on/off switches are the feature flags of the ops context.
 */
@ConfigurationProperties("radar.ingest")
public record IngestScheduleProperties(
    @DefaultValue("6") List<Integer> modalities, @DefaultValue Incremental incremental) {

  public IngestScheduleProperties {
    modalities = List.copyOf(modalities);
  }

  /** Defaults are the ones of ADR-0006; the probe may revise them. */
  public record Incremental(
      @DefaultValue("15m") Duration overlap, @DefaultValue("P1D") Duration initialLookback) {}
}
