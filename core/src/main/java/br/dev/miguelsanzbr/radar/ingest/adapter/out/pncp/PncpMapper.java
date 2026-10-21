package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import br.dev.miguelsanzbr.radar.ingest.application.port.PncpPage;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpProtocolException;
import br.dev.miguelsanzbr.radar.ingest.domain.DisputeMode;
import br.dev.miguelsanzbr.radar.ingest.domain.LegalBasis;
import br.dev.miguelsanzbr.radar.ingest.domain.Modality;
import br.dev.miguelsanzbr.radar.ingest.domain.Organ;
import br.dev.miguelsanzbr.radar.ingest.domain.OrganUnit;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSituation;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSnapshot;
import br.dev.miguelsanzbr.radar.ingest.domain.ProposalWindow;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.function.Function;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/** PNCP page JSON to domain snapshots. */
final class PncpMapper {

  /** PNCP datetimes carry no offset and are local to Brasilia. */
  private static final ZoneId PNCP_ZONE = ZoneId.of("America/Sao_Paulo");

  private static final int MAX_DIGITS = 18;
  private static final int MAX_SCALE = 2;

  private final JsonMapper json;

  PncpMapper(JsonMapper base) {
    // Exact decimals: a double would hide the scale that bounded() checks.
    this.json =
        base.rebuild()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
  }

  PncpPage page(String body) {
    try {
      var root = json.readTree(body);
      var items = new ArrayList<PncpSnapshot>();
      for (var node : root.required("data")) {
        items.add(snapshot(node));
      }
      return new PncpPage(
          items,
          root.required("numeroPagina").asInt(),
          root.required("totalPaginas").asInt(),
          root.required("totalRegistros").asLong(),
          root.required("paginasRestantes").asInt());
    } catch (PncpProtocolException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new PncpProtocolException(0, "unreadable PNCP page: " + e.getMessage(), e);
    }
  }

  private PncpSnapshot snapshot(JsonNode node) {
    try {
      var item = json.treeToValue(node, PncpItem.class);
      return new PncpSnapshot(
          new ProcurementId(item.numeroControlePncp()),
          new PncpSituation(item.situacaoCompraId(), item.situacaoCompraNome()),
          new Organ(item.orgaoEntidade().cnpj(), item.orgaoEntidade().razaoSocial()),
          unit(item.unidadeOrgao()),
          new Modality(item.modalidadeId(), item.modalidadeNome()),
          opt(item.modoDisputaId(), id -> new DisputeMode(id, item.modoDisputaNome())),
          opt(item.amparoLegal(), l -> new LegalBasis(l.codigo(), l.nome())),
          item.srp(),
          opt(item.valorTotalEstimado(), PncpMapper::money),
          opt(item.valorTotalHomologado(), PncpMapper::money),
          new ProposalWindow(
              opt(item.dataAberturaProposta(), PncpMapper::instant),
              opt(item.dataEncerramentoProposta(), PncpMapper::instant)),
          opt(item.dataPublicacaoPncp(), PncpMapper::instant),
          opt(item.dataAtualizacaoGlobal(), PncpMapper::instant),
          item.objetoCompra(),
          node.toString());
    } catch (RuntimeException e) {
      throw new PncpProtocolException(
          0, "item " + node.path("numeroControlePNCP").asString("?") + ": " + e.getMessage(), e);
    }
  }

  private static OrganUnit unit(PncpItem.Unit u) {
    return new OrganUnit(
        u.codigoUnidade(),
        u.nomeUnidade(),
        opt(u.ufSigla(), Uf::of),
        u.municipioNome(),
        opt(u.codigoIbge(), IbgeCode::new));
  }

  private static <T, R> R opt(T value, Function<T, R> map) {
    return value == null ? null : map.apply(value);
  }

  private static Instant instant(String text) {
    try {
      return LocalDateTime.parse(text).atZone(PNCP_ZONE).toInstant();
    } catch (java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException(
          "datetime [" + text + "] is not a local time without offset", e);
    }
  }

  /** Rejects what {@link Money} would normalize at unbounded cost, such as {@code 1E999999999}. */
  private static Money money(BigDecimal value) {
    if (value.precision() > MAX_DIGITS || value.scale() < -MAX_SCALE || value.scale() > MAX_SCALE) {
      throw new IllegalArgumentException("decimal out of bounds: " + value);
    }
    return new Money(value);
  }
}
