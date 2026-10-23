package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import br.dev.miguelsanzbr.radar.api.generated.api.OrgansApi;
import br.dev.miguelsanzbr.radar.api.generated.model.Organ;
import br.dev.miguelsanzbr.radar.api.generated.model.OrganList;
import br.dev.miguelsanzbr.radar.search.application.OrganSearchUseCase;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrganController implements OrgansApi {

  private final OrganSearchUseCase organs;

  OrganController(OrganSearchUseCase organs) {
    this.organs = organs;
  }

  @Override
  public ResponseEntity<OrganList> searchOrgans(String q) {
    Texts.withoutControlCharacters("q", q);
    if (q.isBlank()) {
      throw new ValidationFailure(HttpStatus.BAD_REQUEST, "q", "não pode ser vazio");
    }
    return ResponseEntity.ok(
        new OrganList()
            .items(
                organs.search(q, OrganSearchUseCase.DEFAULT_LIMIT).stream()
                    .map(
                        match ->
                            new Organ()
                                .name(match.organName())
                                // the float's shortest text, not its widened binary expansion
                                .similarity(new BigDecimal(Float.toString(match.similarity()))))
                    .toList()));
  }
}
