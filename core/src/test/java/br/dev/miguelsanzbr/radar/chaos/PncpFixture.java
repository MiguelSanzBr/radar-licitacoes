package br.dev.miguelsanzbr.radar.chaos;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A PNCP that serves {@code DAYS} days of {@code PAGES_PER_DAY} full pages of notices, every notice
 * with its own number. Each page answer waits {@code PAGE_DELAY_MS}, which paces the feed so that
 * the faults of the test meet an ingest that is still running.
 */
final class PncpFixture {

  static final int PAGE_SIZE = 50;
  static final int PAGES_PER_DAY = 40;
  static final int DAYS = 5;
  static final int EVENTS = PAGE_SIZE * PAGES_PER_DAY * DAYS;
  static final int PAGE_DELAY_MS = 1000;

  static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 16);

  private PncpFixture() {}

  static List<LocalDate> days() {
    var days = new ArrayList<LocalDate>();
    for (var i = 0; i < DAYS; i++) {
      days.add(FIRST_DAY.plusDays(i));
    }
    return days;
  }

  /** Registers every page on the WireMock container, through its admin API. */
  static void stubAll() {
    var admin =
        new WireMock(
            ChaosEnvironment.PNCP_STANDIN.getHost(),
            ChaosEnvironment.PNCP_STANDIN.getMappedPort(8080));
    var compact = DateTimeFormatter.BASIC_ISO_DATE;
    var number = 0;
    for (var day : days()) {
      for (var page = 1; page <= PAGES_PER_DAY; page++) {
        var items = new String[PAGE_SIZE];
        for (var i = 0; i < PAGE_SIZE; i++) {
          items[i] = item(number++);
        }
        admin.register(
            get(urlPathEqualTo("/api/consulta/v1/contratacoes/publicacao"))
                .withQueryParam("dataInicial", equalTo(compact.format(day)))
                .withQueryParam("pagina", equalTo(String.valueOf(page)))
                .willReturn(
                    aResponse()
                        .withFixedDelay(PAGE_DELAY_MS)
                        .withHeader("Content-Type", "application/json")
                        .withBody(page(page, items))));
      }
    }
  }

  private static String page(int page, String... items) {
    return """
    {"data":[%s],"totalRegistros":%d,"totalPaginas":%d,"numeroPagina":%d,"paginasRestantes":%d,"empty":false}
    """
        .formatted(
            String.join(",", items),
            PAGES_PER_DAY * PAGE_SIZE,
            PAGES_PER_DAY,
            page,
            PAGES_PER_DAY - page);
  }

  private static String item(int n) {
    return """
    {"numeroControlePNCP":"83021808000182-1-%06d/2026","situacaoCompraId":1,"situacaoCompraNome":"Divulgada no PNCP",
     "orgaoEntidade":{"cnpj":"83021808000182","razaoSocial":"Prefeitura de Exemplo"},
     "unidadeOrgao":{"codigoUnidade":"1010","nomeUnidade":"Compras","ufSigla":"SC",
       "municipioNome":"Exemplo","codigoIbge":"4205407"},
     "modalidadeId":6,"modalidadeNome":"Pregao Eletronico","srp":false,
     "dataPublicacaoPncp":"2026-10-16T09:00:00","dataAtualizacaoGlobal":"2026-10-16T10:00:00",
     "dataEncerramentoProposta":null,
     "objetoCompra":"objeto %d"}
    """
        .formatted(n, n);
  }
}
