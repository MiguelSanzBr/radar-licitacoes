package br.dev.miguelsanzbr.radar.alerts;

import br.dev.miguelsanzbr.radar.alerts.application.port.NotifierMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Keeps what the notifier measured. */
public final class RecordingNotifierMetrics implements NotifierMetrics {

  public final List<Duration> latencies = new ArrayList<>();
  public final List<Kind> failures = new ArrayList<>();
  public int stale;

  @Override
  public void delivered(Duration latency) {
    latencies.add(latency);
  }

  @Override
  public void mailFailed(Kind kind) {
    failures.add(kind);
  }

  @Override
  public void staleSkipped() {
    stale++;
  }
}
