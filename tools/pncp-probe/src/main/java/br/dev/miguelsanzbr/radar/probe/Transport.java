package br.dev.miguelsanzbr.radar.probe;

/** Seam over HTTP so the scheduler runs against fakes. Implementations never throw. */
@FunctionalInterface
public interface Transport {

  Reply send(String url);
}
