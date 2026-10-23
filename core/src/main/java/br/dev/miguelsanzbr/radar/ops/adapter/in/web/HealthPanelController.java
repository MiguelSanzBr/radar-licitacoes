package br.dev.miguelsanzbr.radar.ops.adapter.in.web;

import br.dev.miguelsanzbr.radar.ops.application.HealthPanelUseCase;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The panel as JSON, the contract's {@code HealthPanel}; a section that could not be read is
 * absent.
 */
@RestController
@RequestMapping("/ops")
class HealthPanelController {

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .changeDefaultPropertyInclusion(
              value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
          .build();

  private final HealthPanelUseCase useCase;

  HealthPanelController(HealthPanelUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping(path = "/health-panel", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> healthPanel() {
    return ResponseEntity.ok(JSON.writeValueAsString(useCase.read()));
  }
}
