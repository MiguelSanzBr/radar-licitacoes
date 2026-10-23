package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import br.dev.miguelsanzbr.radar.api.generated.api.ApiKeysApi;
import br.dev.miguelsanzbr.radar.api.generated.model.ApiKeyCreated;
import br.dev.miguelsanzbr.radar.identity.application.ManageApiKeysUseCase;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ApiKeysController implements ApiKeysApi {

  private final ManageApiKeysUseCase keys;

  ApiKeysController(ManageApiKeysUseCase keys) {
    this.keys = keys;
  }

  @Override
  public ResponseEntity<ApiKeyCreated> createApiKey() {
    var issued = keys.issue(subject());
    return ResponseEntity.status(201)
        .cacheControl(CacheControl.noStore())
        .body(new ApiKeyCreated().prefix(issued.key().prefix()).key(issued.secret()));
  }

  @Override
  public ResponseEntity<Void> revokeApiKey(String prefix) {
    keys.revoke(subject(), prefix);
    return ResponseEntity.noContent().build();
  }

  /** The route needs the supplier role, so the chain has authenticated the caller already. */
  private static String subject() {
    return Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication()).getName();
  }
}
