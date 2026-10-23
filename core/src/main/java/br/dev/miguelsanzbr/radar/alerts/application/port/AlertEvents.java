package br.dev.miguelsanzbr.radar.alerts.application.port;

import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import java.time.Instant;

/** Publishes alert events; joins the transaction of the caller. */
public interface AlertEvents {

  void matched(AlertMatched event, Instant occurredAt);
}
