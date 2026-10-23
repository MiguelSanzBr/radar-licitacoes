package br.dev.miguelsanzbr.radar.search.application;

import java.util.List;

/** Read side port of the organ search. */
public interface OrganReadModel {

  /** Distinct organ names similar to {@code q}, most similar first, at most {@code limit}. */
  List<OrganMatch> similarTo(String q, int limit);
}
