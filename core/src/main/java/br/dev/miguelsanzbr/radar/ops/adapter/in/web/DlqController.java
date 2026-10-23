package br.dev.miguelsanzbr.radar.ops.adapter.in.web;

import br.dev.miguelsanzbr.radar.ops.application.ReprocessDlqUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator entry point to put the dead messages of a dead-letter topic back on their topic. */
@RestController
@RequestMapping("/ops")
class DlqController {

  private static final Logger LOG = LoggerFactory.getLogger(DlqController.class);

  record Accepted(String status, String detail, int count, boolean more) {}

  private final ReprocessDlqUseCase useCase;

  DlqController(ReprocessDlqUseCase useCase) {
    this.useCase = useCase;
  }

  @PostMapping("/dlq/{topic}/reprocess")
  ResponseEntity<Accepted> reprocess(
      @PathVariable String topic, @RequestParam(required = false) Long id) {
    var result = useCase.reprocess(topic, id);
    if (result.markFailure() != null) {
      LOG.warn(
          "a message of {} went back but could not be marked, it stays pending: {}",
          topic,
          result.markFailure());
    }
    var detail = result.count() + " mensagem(ns) reenviada(s) ao tópico original";
    return ResponseEntity.accepted()
        .body(
            new Accepted(
                "ACCEPTED",
                result.more() ? detail + "; ainda há pendentes, repita a chamada" : detail,
                result.count(),
                result.more()));
  }
}
