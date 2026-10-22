package br.dev.miguelsanzbr.radar.ingest.adapter.in.ops;

import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;

/**
 * The night window (America/Sao_Paulo, may cross midnight) and the optional nightly catch-up from
 * {@code scheduledFrom} until yesterday.
 */
@ConfigurationProperties("radar.ingest.backfill")
public record BackfillProperties(
    @DefaultValue("00:00") @DateTimeFormat(iso = ISO.TIME) LocalTime allowedFrom,
    @DefaultValue("06:00") @DateTimeFormat(iso = ISO.TIME) LocalTime allowedTo,
    @DefaultValue("false") boolean scheduledEnabled,
    @DateTimeFormat(iso = ISO.DATE) LocalDate scheduledFrom,
    @DefaultValue("6") int scheduledModality) {

  public BackfillProperties {
    if (scheduledEnabled && scheduledFrom == null) {
      throw new IllegalArgumentException(
          "radar.ingest.backfill.scheduled-from is required when scheduled-enabled=true");
    }
  }
}
