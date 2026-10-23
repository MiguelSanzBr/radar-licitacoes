package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import br.dev.miguelsanzbr.radar.search.application.DetailReadModel;
import br.dev.miguelsanzbr.radar.search.application.ProcurementDetail;
import br.dev.miguelsanzbr.radar.search.application.SituationChange;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code procurement_detail_view}: the snapshot as received, plus the history and the etag
 * the projector wrote. Fields this version does not know are ignored.
 */
@Component
class JooqDetailReadModel implements DetailReadModel {

  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private record Organ(String cnpj, String name) {}

  private record Unit(
      String code, String name, String uf, String municipalityName, String ibgeCode) {}

  private record Named(String name) {}

  private record Modality(int id, String name) {}

  private record Window(Instant openingAt, Instant closingAt) {}

  private record Change(Named from, Named to, boolean unexpected, Instant pncpUpdatedAt) {}

  private record Stored(
      String objectDescription,
      Organ organ,
      Unit unit,
      Modality modality,
      Named disputeMode,
      Named legalBasis,
      boolean srp,
      String status,
      BigDecimal estimatedValue,
      BigDecimal awardedValue,
      Window proposalWindow,
      Instant publishedAt,
      Instant pncpUpdatedAt,
      List<Change> history) {}

  private final DSLContext dsl;

  JooqDetailReadModel(@Qualifier("searchReadDsl") DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<ProcurementDetail> find(String id) {
    return dsl.fetchOptional(
            "SELECT detail::text, etag FROM search.procurement_detail_view"
                + " WHERE numero_controle_pncp = ?",
            id)
        .map(
            row ->
                detail(
                    id,
                    JSON.readValue(row.get(0, String.class), Stored.class),
                    row.get(1, String.class)));
  }

  private static ProcurementDetail detail(String id, Stored s, String etag) {
    var window = s.proposalWindow();
    return new ProcurementDetail(
        id,
        s.objectDescription(),
        s.organ().cnpj(),
        s.organ().name(),
        s.unit().code(),
        s.unit().name(),
        s.unit().uf(),
        s.unit().municipalityName(),
        s.unit().ibgeCode(),
        s.modality().id(),
        s.modality().name(),
        mapped(s.disputeMode(), Named::name),
        mapped(s.legalBasis(), Named::name),
        s.srp(),
        s.status(),
        mapped(s.estimatedValue(), Money::new),
        mapped(s.awardedValue(), Money::new),
        mapped(window, Window::openingAt),
        mapped(window, Window::closingAt),
        s.publishedAt(),
        s.pncpUpdatedAt(),
        s.history().stream()
            .map(
                c ->
                    new SituationChange(
                        mapped(c.from(), Named::name),
                        c.to().name(),
                        c.pncpUpdatedAt(),
                        c.unexpected()))
            .toList(),
        etag);
  }

  private static <T, R> R mapped(T value, Function<T, R> map) {
    return value == null ? null : map.apply(value);
  }
}
