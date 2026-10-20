package br.dev.miguelsanzbr.radar.ingest.application.port;

import br.dev.miguelsanzbr.radar.ingest.domain.PncpSnapshot;
import java.util.List;

/** One page of a PNCP listing; a window without data is an empty page with zero total pages. */
public record PncpPage(
    List<PncpSnapshot> items,
    int pageNumber,
    int totalPages,
    long totalRecords,
    int remainingPages) {

  public PncpPage {
    items = List.copyOf(items);
  }

  public boolean isLast() {
    return remainingPages == 0;
  }
}
