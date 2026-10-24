package br.dev.miguelsanzbr.radar.alerts.application.port;

/** Mutual exclusion across application instances for work that must not run twice at once. */
public interface ClusterLock {

  /**
   * Runs {@code work} while holding the cluster-wide lock {@code name}, and returns true. Returns
   * false at once, without running, if another instance holds it. The lock is not a transaction of
   * the work: what the work commits stays committed if it fails later.
   */
  boolean tryRun(String name, Runnable work);
}
