package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import br.dev.miguelsanzbr.radar.alerts.application.EraseSubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.port.CurrentSubscriber;
import br.dev.miguelsanzbr.radar.api.generated.api.MeApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController implements MeApi {

  private final EraseSubscriberUseCase erase;
  private final CurrentSubscriber current;

  MeController(EraseSubscriberUseCase erase, CurrentSubscriber current) {
    this.erase = erase;
    this.current = current;
  }

  @Override
  public ResponseEntity<Void> deleteMe() {
    erase.erase(current.require());
    return ResponseEntity.noContent().build();
  }
}
