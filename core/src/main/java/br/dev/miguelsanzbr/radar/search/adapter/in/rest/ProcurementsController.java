package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import br.dev.miguelsanzbr.radar.api.generated.api.ProcurementsApi;
import br.dev.miguelsanzbr.radar.api.generated.model.ProcurementDetail;
import br.dev.miguelsanzbr.radar.api.generated.model.ProcurementPage;
import br.dev.miguelsanzbr.radar.api.generated.model.ProcurementStatus;
import br.dev.miguelsanzbr.radar.api.generated.model.ProcurementSummary;
import br.dev.miguelsanzbr.radar.api.generated.model.SituationChange;
import br.dev.miguelsanzbr.radar.api.generated.model.SituationHistory;
import br.dev.miguelsanzbr.radar.search.application.DetailUseCase;
import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import br.dev.miguelsanzbr.radar.search.domain.Cursor;
import br.dev.miguelsanzbr.radar.search.domain.Filters;
import br.dev.miguelsanzbr.radar.search.domain.ProcurementIds;
import br.dev.miguelsanzbr.radar.search.domain.ProposalPhase;
import br.dev.miguelsanzbr.radar.search.domain.RankedResult;
import br.dev.miguelsanzbr.radar.search.domain.SearchQuery;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.Uf;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.StringJoiner;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers are plain GETs of the read model. The conditional GET itself (304 on a matching {@code
 * If-None-Match}) is done by Spring for a 200 {@link ResponseEntity} that carries an {@code ETag}.
 */
@RestController
class ProcurementsController implements ProcurementsApi {

  private final SearchUseCase search;
  private final DetailUseCase detail;
  private final Clock clock;
  private final JsonMapper json;

  ProcurementsController(SearchUseCase search, DetailUseCase detail, Clock clock, JsonMapper json) {
    this.search = search;
    this.detail = detail;
    this.clock = clock;
    this.json = json;
  }

  @Override
  public ResponseEntity<ProcurementPage> searchProcurements(
      String q,
      String uf,
      String ibge,
      Integer modality,
      String minValue,
      String maxValue,
      Boolean open,
      ProcurementStatus status,
      String cursor,
      Integer limit,
      String ifNoneMatch) {
    var min = parsed("minValue", minValue, Money::of);
    var max = parsed("maxValue", maxValue, Money::of);
    if (min != null && max != null && min.compareTo(max) > 0) {
      throw new ValidationFailure(
          HttpStatus.UNPROCESSABLE_ENTITY, "minValue", "maior que maxValue");
    }
    var filters =
        new Filters(
            parsed("uf", uf, Uf::of),
            parsed("ibge", ibge, IbgeCode::new),
            modality,
            min,
            max,
            open,
            status == null ? null : status.getValue());
    var page =
        search.search(
            new SearchQuery(
                Texts.withoutControlCharacters("q", q),
                filters,
                cursor == null ? null : Cursor.decode(cursor),
                limit));
    var now = clock.instant();
    var body =
        new ProcurementPage()
            .items(page.items().stream().map(hit -> summary(hit, now)).toList())
            .nextCursor(page.nextCursor() == null ? null : page.nextCursor().encode())
            .limit(limit);
    var tag = ETags.weak(json.writeValueAsString(body));
    if (ETags.matchesAny(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(tag).build();
    }
    var response = ResponseEntity.ok().eTag(tag);
    if (page.nextCursor() != null) {
      response.header(HttpHeaders.LINK, next(body.getNextCursor()));
    }
    return response.body(body);
  }

  @Override
  public ResponseEntity<ProcurementDetail> getProcurement(String id, String ifNoneMatch) {
    var stored = detail.find(ProcurementIds.fromPath(id));
    // the phase moves with the clock, so the validator covers it as well as the stored version
    var phase = phase(stored.proposalOpeningAt(), stored.proposalClosingAt(), clock.instant());
    var etag = ETags.sha256(stored.etag() + ":" + phase.getValue());
    var body =
        new ProcurementDetail()
            .id(ProcurementIds.toPath(stored.id()))
            .objectDescription(stored.objectDescription())
            .organCnpj(stored.organCnpj())
            .organName(stored.organName())
            .unitCode(stored.unitCode())
            .unitName(stored.unitName())
            .uf(stored.uf())
            .municipalityName(stored.municipalityName())
            .ibgeCode(stored.ibgeCode())
            .modalityId(stored.modalityId())
            .modalityName(stored.modalityName())
            .disputeMode(stored.disputeMode())
            .legalBasis(stored.legalBasis())
            .srp(stored.srp())
            .status(ProcurementStatus.fromValue(stored.status()))
            .estimatedValue(text(stored.estimatedValue()))
            .awardedValue(text(stored.awardedValue()))
            .proposalOpeningAt(offset(stored.proposalOpeningAt()))
            .proposalClosingAt(offset(stored.proposalClosingAt()))
            .proposalPhase(phase)
            .publishedAt(offset(stored.publishedAt()))
            .pncpUpdatedAt(offset(stored.pncpUpdatedAt()))
            .history(stored.history().stream().map(ProcurementsController::change).toList())
            .etag(etag);
    if (ETags.matchesAny(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag("\"" + etag + "\"").build();
    }
    return ResponseEntity.ok().eTag("\"" + etag + "\"").body(body);
  }

  @Override
  public ResponseEntity<SituationHistory> getProcurementHistory(String id) {
    return ResponseEntity.ok(
        new SituationHistory()
            .items(
                detail.find(ProcurementIds.fromPath(id)).history().stream()
                    .map(ProcurementsController::change)
                    .toList()));
  }

  private static ProcurementSummary summary(RankedResult hit, Instant now) {
    return new ProcurementSummary()
        .id(ProcurementIds.toPath(hit.id()))
        .objectDescription(hit.objectDescription())
        .snippet(hit.snippet())
        .organName(hit.organName())
        .unitName(hit.unitName())
        .uf(hit.uf())
        .municipalityName(hit.municipalityName())
        .ibgeCode(hit.ibgeCode())
        .modalityId(hit.modalityId())
        .status(ProcurementStatus.fromValue(hit.status()))
        .estimatedValue(text(hit.estimatedValue()))
        .proposalOpeningAt(offset(hit.proposalOpeningAt()))
        .proposalClosingAt(offset(hit.proposalClosingAt()))
        .proposalPhase(phase(hit.proposalOpeningAt(), hit.proposalClosingAt(), now))
        .publishedAt(offset(hit.publishedAt()))
        .rank(hit.rank() == null ? null : BigDecimal.valueOf(hit.rank()));
  }

  private static SituationChange change(
      br.dev.miguelsanzbr.radar.search.application.SituationChange change) {
    return new SituationChange()
        .from(change.from())
        .to(change.to())
        .pncpUpdatedAt(offset(change.pncpUpdatedAt()))
        .unexpected(change.unexpected());
  }

  /**
   * The same query with {@code cursor} replaced, each name and value encoded again from what the
   * servlet decoded (so raw non-ASCII input is fine). A {@code +} is encoded too: the encoder
   * leaves it alone, but a server reads it back as a space.
   */
  private static String next(String cursor) {
    var query = new StringJoiner("&");
    currentRequest()
        .getParameterMap()
        .forEach(
            (name, values) -> {
              if (!name.equals("cursor")) {
                for (var value : values) {
                  query.add(encoded(name) + "=" + encoded(value));
                }
              }
            });
    query.add("cursor=" + cursor);
    return "<" + PATH_SEARCH_PROCUREMENTS + "?" + query + ">; rel=\"next\"";
  }

  /** Read per call: the controller is a singleton and the context may have no web request. */
  private static HttpServletRequest currentRequest() {
    return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
        .getRequest();
  }

  private static String encoded(String text) {
    return UriUtils.encodeQueryParam(text, StandardCharsets.UTF_8).replace("+", "%2B");
  }

  /** A malformed value is the caller's mistake, named by its parameter. */
  private static <T> T parsed(String field, String value, Function<String, T> parse) {
    try {
      return value == null ? null : parse.apply(value);
    } catch (IllegalArgumentException e) {
      throw new ValidationFailure(HttpStatus.BAD_REQUEST, field, "valor inválido");
    }
  }

  private static br.dev.miguelsanzbr.radar.api.generated.model.ProposalPhase phase(
      Instant opening, Instant closing, Instant now) {
    return br.dev.miguelsanzbr.radar.api.generated.model.ProposalPhase.valueOf(
        ProposalPhase.at(opening, closing, now).name());
  }

  private static String text(Money money) {
    return money == null ? null : money.amount().toPlainString();
  }

  private static OffsetDateTime offset(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
