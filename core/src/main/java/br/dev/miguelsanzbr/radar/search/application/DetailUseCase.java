package br.dev.miguelsanzbr.radar.search.application;

/** Detail and situation history of one procurement. */
public class DetailUseCase {

  private final DetailReadModel readModel;

  public DetailUseCase(DetailReadModel readModel) {
    this.readModel = readModel;
  }

  /**
   * @throws ProcurementNotFoundException when nothing is projected under {@code id}
   */
  public ProcurementDetail find(String id) {
    return readModel.find(id).orElseThrow(() -> new ProcurementNotFoundException(id));
  }
}
