package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyGuard;
import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyGuard.Stored;
import br.dev.miguelsanzbr.radar.alerts.application.ManageSavedSearchUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.port.CurrentSubscriber;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertFilters;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedQuery;
import br.dev.miguelsanzbr.radar.alerts.domain.Sha256;
import br.dev.miguelsanzbr.radar.api.generated.api.SavedSearchesApi;
import br.dev.miguelsanzbr.radar.api.generated.model.SavedSearch;
import br.dev.miguelsanzbr.radar.api.generated.model.SavedSearchFilters;
import br.dev.miguelsanzbr.radar.api.generated.model.SavedSearchRequest;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
class SavedSearchController implements SavedSearchesApi {

  /** Alphabetical keys, so equal bodies hash equal whatever the client's field order. */
  private static final JsonMapper CANONICAL =
      JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

  private final ManageSavedSearchUseCase manage;
  private final IdempotencyGuard guard;
  private final CurrentSubscriber current;
  private final JsonMapper json;

  SavedSearchController(
      ManageSavedSearchUseCase manage,
      IdempotencyGuard guard,
      CurrentSubscriber current,
      JsonMapper json) {
    this.manage = manage;
    this.guard = guard;
    this.current = current;
    this.json = json;
  }

  @Override
  public ResponseEntity<SavedSearch> createSavedSearch(
      String idempotencyKey, SavedSearchRequest request) {
    var subscriber = current.require();
    var requestHash = Sha256.hash(CANONICAL.writeValueAsString(request));
    var result =
        guard.run(
            subscriber,
            idempotencyKey,
            requestHash,
            () -> {
              var created =
                  manage.create(
                      subscriber,
                      request.getName(),
                      new SavedQuery(request.getQuery(), filters(request.getFilters())),
                      Frequency.valueOf(request.getFrequency().name()));
              return new Stored(201, json.writeValueAsString(dto(created)));
            });
    var body = json.readValue(result.stored().body(), SavedSearch.class);
    var response =
        ResponseEntity.status(result.stored().status())
            .location(URI.create("/api/v1/saved-searches/" + body.getId()));
    return (result.replayed() ? response.header("Idempotent-Replayed", "true") : response)
        .body(body);
  }

  @Override
  public ResponseEntity<SavedSearch> getSavedSearch(UUID id) {
    return ResponseEntity.ok(dto(manage.get(current.require(), id)));
  }

  @Override
  public ResponseEntity<Void> deleteSavedSearch(UUID id) {
    manage.delete(current.require(), id);
    return ResponseEntity.noContent().build();
  }

  private static AlertFilters filters(SavedSearchFilters dto) {
    return dto == null
        ? AlertFilters.NONE
        : new AlertFilters(
            dto.getUf(),
            dto.getIbge(),
            dto.getModality(),
            dto.getMinValue(),
            dto.getMaxValue(),
            dto.getOpen());
  }

  private static SavedSearch dto(br.dev.miguelsanzbr.radar.alerts.domain.SavedSearch search) {
    var filters = search.query().filters();
    return new SavedSearch()
        .id(search.id())
        .name(search.name())
        .query(search.query().text())
        .filters(
            new SavedSearchFilters()
                .uf(filters.uf())
                .ibge(filters.ibge())
                .modality(filters.modality())
                .minValue(filters.minValue())
                .maxValue(filters.maxValue())
                .open(filters.open()))
        .frequency(
            br.dev.miguelsanzbr.radar.api.generated.model.Frequency.valueOf(
                search.frequency().name()))
        .active(search.active())
        .createdAt(OffsetDateTime.ofInstant(search.createdAt(), ZoneOffset.UTC));
  }
}
