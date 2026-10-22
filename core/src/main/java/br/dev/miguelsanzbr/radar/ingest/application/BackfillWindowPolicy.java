package br.dev.miguelsanzbr.radar.ingest.application;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/** When the scheduled backfill may call the PNCP: a daily window that may cross midnight. */
public record BackfillWindowPolicy(LocalTime from, LocalTime to, ZoneId zone) {

  /** {@code from} is inside the window and {@code to} is not. */
  public boolean allowed(Instant now) {
    var time = now.atZone(zone).toLocalTime();
    return from.isBefore(to)
        ? !time.isBefore(from) && time.isBefore(to)
        : !time.isBefore(from) || time.isBefore(to);
  }
}
