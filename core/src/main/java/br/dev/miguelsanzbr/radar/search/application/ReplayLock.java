package br.dev.miguelsanzbr.radar.search.application;

import java.util.Optional;

/** Cluster-wide exclusion for the rebuild: only one instance may replay at a time. */
public interface ReplayLock {

  /** The held lock, released by {@link Held#close()}; empty when another instance holds it. */
  Optional<Held> tryAcquire();

  /** A lock held until closed. A process that dies releases it too. */
  interface Held extends AutoCloseable {
    @Override
    void close();
  }
}
