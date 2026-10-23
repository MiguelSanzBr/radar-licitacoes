package br.dev.miguelsanzbr.radar.ops;

/** Reads the flags; the only way the other contexts see the feature flag library. */
@FunctionalInterface
public interface Flags {

  boolean isActive(Flag flag);
}
