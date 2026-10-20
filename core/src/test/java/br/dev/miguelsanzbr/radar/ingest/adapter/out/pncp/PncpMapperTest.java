package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.port.PncpProtocolException;
import br.dev.miguelsanzbr.radar.ingest.domain.DisputeMode;
import br.dev.miguelsanzbr.radar.ingest.domain.LegalBasis;
import br.dev.miguelsanzbr.radar.ingest.domain.Modality;
import br.dev.miguelsanzbr.radar.ingest.domain.Organ;
import br.dev.miguelsanzbr.radar.ingest.domain.OrganUnit;
import br.dev.miguelsanzbr.radar.ingest.domain.PncpSituation;
import br.dev.miguelsanzbr.radar.ingest.domain.ProposalWindow;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class PncpMapperTest {

  private static final String FIRST_ID = "83021808000182-1-000681/2026";

  private final JsonMapper json = JsonMapper.builder().build();
  private final PncpMapper mapper = new PncpMapper(json);

  static String fixture(String name) throws IOException {
    try (var in = PncpMapperTest.class.getResourceAsStream("/wiremock/pncp/" + name)) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  private String page1() throws IOException {
    return fixture("publicacao-page1.json");
  }

  @Test
  void mapsEveryFieldOfTheFirstItem() throws IOException {
    var page = mapper.page(page1());
    var s = page.items().get(0);

    assertThat(s.id()).isEqualTo(new ProcurementId(FIRST_ID));
    assertThat(s.situation()).isEqualTo(new PncpSituation(1, "Divulgada no PNCP"));
    assertThat(s.organ()).isEqualTo(new Organ("83021808000182", "MUNICIPIO DE CHAPECO"));
    assertThat(s.unit())
        .isEqualTo(
            new OrganUnit(
                "1", "Prefeitura Municipal de Chapecó", Uf.SC, "Chapecó", new IbgeCode("4204202")));
    assertThat(s.modality()).isEqualTo(new Modality(6, "Pregão - Eletrônico"));
    assertThat(s.disputeMode()).isEqualTo(new DisputeMode(1, "Aberto"));
    assertThat(s.legalBasis()).isEqualTo(new LegalBasis(1, "Lei 14.133/2021, Art. 28, I"));
    assertThat(s.srp()).isFalse();
    assertThat(s.estimatedValue()).isEqualTo(Money.of("441560.04"));
    assertThat(s.awardedValue()).isNull();
    assertThat(s.objectDescription()).startsWith("CONTRATAÇÃO DE EMPRESA ESPECIALIZADA");
    assertThat(page.items().get(2).srp()).isTrue();
    assertThat(page.items().get(2).estimatedValue()).isEqualTo(Money.of("47723.50"));
  }

  @Test
  void convertsLocalSaoPauloTimesToInstants() throws IOException {
    var s = mapper.page(page1()).items().get(0);

    assertThat(s.publishedAt()).isEqualTo(Instant.parse("2026-10-20T03:00:36Z"));
    assertThat(s.pncpUpdatedAt()).isEqualTo(Instant.parse("2026-10-20T03:00:48Z"));
    assertThat(s.proposalWindow())
        .isEqualTo(
            new ProposalWindow(
                Instant.parse("2026-10-19T20:00:01Z"), Instant.parse("2026-11-03T11:55:01Z")));
  }

  @Test
  void keepsTheOriginalItemJsonAsRaw() throws IOException {
    var original = json.readTree(page1()).get("data").get(0);

    var raw = mapper.page(page1()).items().get(0).rawJson();

    assertThat(json.readTree(raw)).isEqualTo(original);
  }

  @Test
  void readsThePageMetadata() throws IOException {
    var first = mapper.page(page1());
    var second = mapper.page(fixture("publicacao-page2.json"));

    assertThat(first.items()).hasSize(3);
    assertThat(first.pageNumber()).isEqualTo(1);
    assertThat(first.totalPages()).isEqualTo(2);
    assertThat(first.totalRecords()).isEqualTo(4);
    assertThat(first.remainingPages()).isEqualTo(1);
    assertThat(first.isLast()).isFalse();
    assertThat(second.items()).hasSize(1);
    assertThat(second.isLast()).isTrue();
  }

  @Test
  void missingOrNullObjetoCompraBecomesEmpty() throws IOException {
    var absent = page1().replace("\"objetoCompra\"", "\"outroCampo\"");
    var nulled =
        page1().replaceFirst("\"objetoCompra\"\\s*:\\s*\"[^\"]*\"", "\"objetoCompra\":null");

    assertThat(mapper.page(absent).items().get(0).objectDescription()).isEmpty();
    assertThat(mapper.page(nulled).items().get(0).objectDescription()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-10-20T00:00:36-03:00", "2026-10-20T00:00:36Z", "2026-10-20"})
  void datetimeWithOffsetOrWithoutTimeIsAMappingFailure(String bad) throws IOException {
    var body =
        page1()
            .replace(
                "\"dataPublicacaoPncp\": \"2026-10-20T00:00:36\"",
                "\"dataPublicacaoPncp\": \"" + bad + "\"");

    assertThatThrownBy(() -> mapper.page(body))
        .isInstanceOf(PncpProtocolException.class)
        .hasMessageContaining(FIRST_ID)
        .hasMessageContaining(bad);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1E999999999", "12345678901234567890.12", "1.234", "1E+3", "-1.00"})
  void unboundedOrNegativeDecimalsAreRejectedBeforeMoney(String bad) throws IOException {
    var body = page1().replace("441560.04", bad);

    assertThatThrownBy(() -> mapper.page(body))
        .isInstanceOf(PncpProtocolException.class)
        .hasMessageContaining(FIRST_ID);
  }

  @Test
  void awardedValueIsBoundedToo() throws IOException {
    var bad =
        page1()
            .replaceFirst(
                "\"valorTotalHomologado\"\\s*:\\s*null", "\"valorTotalHomologado\":1E999999999");
    var good =
        page1()
            .replaceFirst("\"valorTotalHomologado\"\\s*:\\s*null", "\"valorTotalHomologado\":10.5");

    assertThatThrownBy(() -> mapper.page(bad)).isInstanceOf(PncpProtocolException.class);
    assertThat(mapper.page(good).items().get(0).awardedValue()).isEqualTo(Money.of("10.50"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"12", "1E+2", "9999999999999999.99", "0.5"})
  void boundedDecimalsAreAccepted(String ok) throws IOException {
    var body = page1().replace("441560.04", ok);

    assertThat(mapper.page(body).items().get(0).estimatedValue())
        .isEqualTo(Money.of(new java.math.BigDecimal(ok).movePointRight(2).longValueExact()));
  }

  @Test
  void optionalFieldsMayBeMissing() throws IOException {
    var body =
        page1()
            .replace("\"ufSigla\"", "\"x1\"")
            .replace("\"codigoIbge\"", "\"x2\"")
            .replace("\"modoDisputaId\"", "\"x3\"")
            .replace("\"amparoLegal\"", "\"x4\"")
            .replace("\"dataAberturaProposta\"", "\"x5\"")
            .replace("\"dataEncerramentoProposta\"", "\"x6\"")
            .replace("\"valorTotalEstimado\"", "\"x7\"");

    var s = mapper.page(body).items().get(0);

    assertThat(s.unit().uf()).isNull();
    assertThat(s.unit().ibgeCode()).isNull();
    assertThat(s.disputeMode()).isNull();
    assertThat(s.legalBasis()).isNull();
    assertThat(s.proposalWindow()).isNull();
    assertThat(s.estimatedValue()).isNull();
  }

  @Test
  void invalidItemFailsWithItsControlNumber() throws IOException {
    var body = page1().replace("\"cnpj\": \"83021808000182\"", "\"cnpj\": \"123\"");

    assertThatThrownBy(() -> mapper.page(body))
        .isInstanceOf(PncpProtocolException.class)
        .hasMessageContaining(FIRST_ID)
        .hasMessageContaining("cnpj");
  }

  @Test
  void missingRequiredObjectsFail() throws IOException {
    var body = page1().replace("\"orgaoEntidade\"", "\"x\"");

    assertThatThrownBy(() -> mapper.page(body)).isInstanceOf(PncpProtocolException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"not json", "{}", "{\"data\":[]}"})
  void unreadablePagesFail(String body) {
    assertThatThrownBy(() -> mapper.page(body))
        .isInstanceOf(PncpProtocolException.class)
        .hasMessageContaining("unreadable PNCP page");
  }
}
