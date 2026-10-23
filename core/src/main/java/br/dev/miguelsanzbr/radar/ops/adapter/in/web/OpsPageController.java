package br.dev.miguelsanzbr.radar.ops.adapter.in.web;

import br.dev.miguelsanzbr.radar.ops.application.HealthPanelUseCase;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The panel for a browser (operator session): the same data as the JSON, rendered on the server.
 */
@Controller
class OpsPageController {

  private final HealthPanelUseCase useCase;

  OpsPageController(HealthPanelUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping("/ops/painel")
  String panel(Model model) {
    model.addAttribute("panel", useCase.read());
    return "ops/painel";
  }
}
