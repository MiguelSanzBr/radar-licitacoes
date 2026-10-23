package br.dev.miguelsanzbr.radar.alerts.application.port;

import java.util.function.Supplier;

/** Measures of the matcher: how long one event takes and how many alerts it produces. */
public interface MatcherMetrics {

  <T> T timed(Supplier<T> work);

  void matched(int alerts);
}
