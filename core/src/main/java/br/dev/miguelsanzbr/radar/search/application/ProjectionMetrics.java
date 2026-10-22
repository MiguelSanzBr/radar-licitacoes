package br.dev.miguelsanzbr.radar.search.application;

import java.time.Duration;

/** Port of the projector metrics. */
public interface ProjectionMetrics {

  /** Time between the domain event and its projection. */
  void lag(Duration lag);

  /** An event was left unprojected because its procurement is not projected yet. */
  void deferred();
}
