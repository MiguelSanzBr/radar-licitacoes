package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import br.dev.miguelsanzbr.radar.api.generated.api.StatsApi;
import br.dev.miguelsanzbr.radar.api.generated.model.StatsBucket;
import br.dev.miguelsanzbr.radar.api.generated.model.StatsResponse;
import br.dev.miguelsanzbr.radar.api.generated.model.StatsResponse.GroupByEnum;
import br.dev.miguelsanzbr.radar.search.application.GroupBy;
import br.dev.miguelsanzbr.radar.search.application.StatsUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatsController implements StatsApi {

  private final StatsUseCase stats;

  StatsController(StatsUseCase stats) {
    this.stats = stats;
  }

  @Override
  public ResponseEntity<StatsResponse> getStats(String groupBy) {
    GroupByEnum dimension;
    try {
      dimension = GroupByEnum.fromValue(groupBy);
    } catch (IllegalArgumentException e) {
      throw new ValidationFailure(HttpStatus.BAD_REQUEST, "groupBy", "valor inválido");
    }
    // the generated enum names are the use case's: UF, MODALITY, MONTH, VALUE_BAND
    var rows = stats.stats(GroupBy.valueOf(dimension.name()));
    return ResponseEntity.ok(
        new StatsResponse()
            .groupBy(dimension)
            .buckets(
                rows.stream()
                    .map(
                        row ->
                            new StatsBucket()
                                .key(row.key())
                                .count(row.count())
                                .estimatedSum(row.estimatedSum().toPlainString()))
                    .toList()));
  }
}
