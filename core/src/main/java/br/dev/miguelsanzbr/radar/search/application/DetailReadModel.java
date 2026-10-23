package br.dev.miguelsanzbr.radar.search.application;

import java.util.Optional;

/** Read side port of the detail use case. */
public interface DetailReadModel {

  /**
   * @param id the control number as stored, with the slash
   */
  Optional<ProcurementDetail> find(String id);
}
