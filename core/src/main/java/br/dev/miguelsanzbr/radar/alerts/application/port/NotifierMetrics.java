package br.dev.miguelsanzbr.radar.alerts.application.port;

import java.time.Duration;
import java.util.Locale;

public interface NotifierMetrics {

  /** What a mail was for, as the failure counter tags it. */
  enum Kind {
    INSTANT,
    DIGEST,
    VERIFICATION;

    public String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Time from the match to the mail server accepting the alert. */
  void delivered(Duration latency);

  /** The mail server did not accept a message; counted on every refused try. */
  void mailFailed(Kind kind);

  /** An alert event older than the maximum age was ignored. */
  void staleSkipped();
}
