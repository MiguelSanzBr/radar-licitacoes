package br.dev.miguelsanzbr.radar.search.application;

import java.util.Collection;
import java.util.List;

/**
 * Drops cached details when the projection changes them. No method may throw: the cache is an
 * optimisation, and the projection has already committed when they run.
 */
public interface DetailCacheInvalidation {

  /** The detail of one procurement, after the projection of an event for it committed. */
  default void invalidate(String id) {
    invalidateAll(List.of(id));
  }

  /** Several details at once (one round trip), after the batch that changed them committed. */
  void invalidateAll(Collection<String> ids);

  /** Every cached detail, after the read model was emptied for a replay. */
  void clear();
}
