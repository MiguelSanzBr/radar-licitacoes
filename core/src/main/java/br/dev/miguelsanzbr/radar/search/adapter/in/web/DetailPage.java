package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import br.dev.miguelsanzbr.radar.search.application.DetailUseCase;
import br.dev.miguelsanzbr.radar.search.domain.ProcurementIds;
import java.time.Clock;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** One procurement with its situation history, from the same use case as the REST API. */
@Controller
class DetailPage {

  private final DetailUseCase detail;
  private final Clock clock;

  DetailPage(DetailUseCase detail, Clock clock) {
    this.detail = detail;
    this.clock = clock;
  }

  @GetMapping("/licitacoes/{id}")
  String page(@PathVariable String id, Model model) {
    var stored = detail.find(ProcurementIds.fromPath(id));
    model.addAttribute("d", DetailView.of(stored, clock.instant()));
    return "search/detail";
  }
}
