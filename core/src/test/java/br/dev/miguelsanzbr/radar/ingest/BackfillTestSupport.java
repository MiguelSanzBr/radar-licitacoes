package br.dev.miguelsanzbr.radar.ingest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.stream.Collectors;

/** One WireMock acting as the PNCP for every backfill integration test of the JVM. */
final class BackfillTestSupport {

  static final WireMockServer PNCP =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    PNCP.start();
  }

  static final String PUBLICATION = "/api/consulta/v1/contratacoes/publicacao";

  private BackfillTestSupport() {}

  /** A clock the tests move: the night window policy and the ops endpoint read it. */
  static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-10-22T05:00:00Z");

    void set(Instant instant) {
      now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  static String id(int n) {
    return "83021808000182-1-%06d/2026".formatted(n);
  }

  static String itemJson(int n) {
    return """
    {"numeroControlePNCP":"%s","situacaoCompraId":1,"situacaoCompraNome":"Divulgada no PNCP",
     "orgaoEntidade":{"cnpj":"83021808000182","razaoSocial":"Prefeitura de Exemplo"},
     "unidadeOrgao":{"codigoUnidade":"1010","nomeUnidade":"Compras","ufSigla":"SC",
       "municipioNome":"Exemplo","codigoIbge":"4205407"},
     "modalidadeId":6,"modalidadeNome":"Pregao Eletronico","srp":false,
     "dataPublicacaoPncp":"2026-10-20T09:00:00","dataAtualizacaoGlobal":"2026-10-20T10:00:00",
     "objetoCompra":"objeto %d"}
    """
        .formatted(id(n), n);
  }

  static String pageJson(int page, int totalPages, int... ids) {
    var items =
        Arrays.stream(ids).mapToObj(BackfillTestSupport::itemJson).collect(Collectors.joining(","));
    return """
    {"data":[%s],"totalRegistros":%d,"totalPaginas":%d,"numeroPagina":%d,"paginasRestantes":%d,"empty":false}
    """
        .formatted(items, ids.length, totalPages, page, totalPages - page);
  }

  /** A request for one page of one day, in the PNCP's yyyyMMdd form. */
  static MappingBuilder pageRequest(String day, int page) {
    return get(urlPathEqualTo(PUBLICATION))
        .withQueryParam("dataInicial", equalTo(day))
        .withQueryParam("pagina", equalTo(String.valueOf(page)));
  }

  static void stubPage(String day, int page, int totalPages, int... ids) {
    PNCP.stubFor(
        pageRequest(day, page)
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(pageJson(page, totalPages, ids))));
  }
}
