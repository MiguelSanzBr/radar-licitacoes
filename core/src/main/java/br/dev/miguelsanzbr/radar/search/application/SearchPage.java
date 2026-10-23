package br.dev.miguelsanzbr.radar.search.application;

import br.dev.miguelsanzbr.radar.search.domain.Cursor;
import br.dev.miguelsanzbr.radar.search.domain.RankedResult;
import java.util.List;

/** One page of hits; {@code nextCursor} is null on the last page. */
public record SearchPage(List<RankedResult> items, Cursor nextCursor) {

  public SearchPage {
    items = List.copyOf(items);
  }
}
