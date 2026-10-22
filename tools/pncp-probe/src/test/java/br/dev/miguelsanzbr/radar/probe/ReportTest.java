package br.dev.miguelsanzbr.radar.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ReportTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final LocalDate DAY = LocalDate.of(2026, 10, 22);

  private static List<JsonNode> fixture() throws IOException {
    Path file = Path.of("src/test/resources/full.jsonl");
    List<JsonNode> rows = new ArrayList<>();
    for (String line : Files.readAllLines(file)) {
      rows.add(JSON.readTree(line));
    }
    return rows;
  }

  private static List<JsonNode> rows(String... lines) {
    return java.util.Arrays.stream(lines).map(JSON::readTree).toList();
  }

  private static String s3(int step, double rate, int status) {
    return """
        {"ts":"2026-10-23T03:00:0%d-03:00","job":"S3","endpoint":"/p","status":%d,"latencyMs":10,\
        "headers":{},"step":%d,"rate":%s}"""
        .formatted(step, status, step, rate);
  }

  private static String s5(int pass, String window, String start, String entries) {
    return """
        {"ts":"2026-10-22T10:00:00-03:00","job":"S5","endpoint":"/a","status":200,"latencyMs":10,\
        "headers":{},"pass":%d,"window":"%s","windowStart":"%s","windowEnd":"%s","page":1,\
        "entries":[%s]}"""
        .formatted(pass, window, start, start, entries);
  }

  private static String entry(String updated, String global) {
    return "{\"id\":\"i\",\"dataAtualizacao\":\"%s\",\"dataAtualizacaoGlobal\":\"%s\",\"hash\":\"h\"}"
        .formatted(updated, global);
  }

  private static final String IN = "2026-10-22T09:00:00-03:00";

  @Test
  void fullFixtureRendersEverySectionWithComputedValues() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md).startsWith("# Relatório da sonda PNCP\n");
    assertThat(md)
        .contains("Data: 2026-10-22")
        .contains("28 requisições, 6 com erro")
        .contains("2026-10-22T10:00:00-03:00 a 2026-10-23T03:00:09-03:00");
    for (String title :
        List.of(
            "## S1 Latência por endpoint e hora do dia",
            "## S2 Erros e headers de limite",
            "## S3 Rampa de taxa",
            "## S4 Paginação profunda",
            "## S5 Semântica da atualização",
            "## S6 Endpoints opcionais",
            "## S7 Volume",
            "## S8 Tamanho da resposta",
            "## S9 robots.txt",
            "## S10 Estabilidade dos campos",
            "## Decisões sugeridas")) {
      assertThat(md).contains(title);
    }
    assertThat(md).doesNotContain("Sem dados.\n").doesNotContain("\u2014").doesNotContain("\u2013");
  }

  @Test
  void s1ShowsNearestRankPercentilesPerEndpointAndHour() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md)
        .contains("| /api/consulta/v1/contratacoes/publicacao | 10 | 1 | 100 | 100 | 100 | 0 |")
        .contains("| /api/consulta/v1/contratacoes/publicacao | 11 | 2 | 150 | 400 | 400 | 0 |")
        .contains("| /api/consulta/v1/contratacoes/publicacao | todas | 3 | 150 | 400 | 400 | 0 |")
        .contains("| /api/consulta/v1/contratacoes/atualizacao | todas | 1 | 200 | 200 | 200 | 1 |")
        .contains("| /api/consulta/v1/contratacoes/atualizacao | 11 | 0 | - | - | - | 1 |")
        .contains("percentis pelo método nearest-rank");
  }

  @Test
  void s2ListsStatusesErrorRateByHourAndLimitHeaders() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md)
        .contains("| 200 | 22 |")
        .contains("| -1 | 3 |")
        .contains("| 429 | 1 |")
        .contains("| 03 | 6 | 1 | 16,7% |")
        .contains("| content-type | 23 |")
        .contains("Headers de limite presentes: ratelimit-limit, retry-after");
  }

  @Test
  void s2SaysNoneWhenThereAreNoLimitHeaders() {
    String md = Report.render(rows(s3(0, 0.5, 200)), DAY);

    assertThat(md).contains("Headers de limite presentes: nenhum");
  }

  @Test
  void s3TableStopAndRateDecisionAfterAnError() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md)
        .contains("| 0 | 0,5 | 2 | 2 | 0 | 0 | 100 |")
        .contains("| 2 | 1,5 | 2 | 1 | 1 | 0 | 50 |")
        .contains("Parou no primeiro 429/403: degrau 2, HTTP 429")
        .contains("maior taxa sem erro 1,0 req/s")
        .contains("usar 0,5 req/s");
  }

  @Test
  void rateDecisionIsOneRequestPerSecondWhenNothingErred() {
    String md = Report.render(rows(s3(0, 0.5, 200), s3(1, 1.0, 200)), DAY);

    assertThat(md)
        .contains("Nenhum 429 ou 403 na rampa")
        .contains("Nenhum erro de taxa até 4 req/s: usar 1,0 req/s")
        .contains("Timeouts por degrau: nenhum");
  }

  @Test
  void rateDecisionKeepsTheBaseRateWhenTheFirstStepAlreadyErred() {
    String md = Report.render(rows(s3(0, 0.5, 429)), DAY);

    assertThat(md).contains("Erro de taxa já no primeiro degrau: manter 0,5 req/s");
  }

  @Test
  void aTimeoutIsNotARateErrorButIsPrintedPerStep() {
    String md = Report.render(rows(s3(0, 0.5, 200), s3(1, 1.0, -1), s3(1, 1.0, -1)), DAY);

    assertThat(md)
        .contains("| 1 | 1,0 | 2 | 0 | 0 | 2 | 10 |")
        .contains("Nenhum erro de taxa até 4 req/s: usar 1,0 req/s")
        .contains("Timeouts por degrau: degrau 1: 2");
  }

  @Test
  void aServerErrorCountsAsARateError() {
    String md = Report.render(rows(s3(0, 0.5, 200), s3(1, 1.0, 503)), DAY);

    assertThat(md)
        .contains("maior taxa sem erro 0,5 req/s")
        .contains("usar 0,3 req/s")
        .contains("| 1 | 1,0 | 1 | 0 | 1 | 0 | 10 |");
  }

  @Test
  void s4ComparesThePassesForDriftDuplicatesAndMissingIds() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md)
        .contains("| 1 | 1 | 200 | 100 | 1000 | 3 |")
        .contains("| 1 | 10 | 200 | 900 | 1000 | 2 |")
        .contains("totalRegistros: passada 1 = 1000, passada 2 = 1002 (variou em 2)")
        .contains("- Passada 1: 1 id(s) repetido(s) entre páginas")
        .contains("- Passada 2: 0 id(s) repetido(s) entre páginas")
        .contains("- Página 1: 1 ausente(s) e 1 novo(s) na passada 2")
        .contains("- Página 10: 2 ausente(s) e 2 novo(s) na passada 2");
  }

  @Test
  void s4WithOnePassOnlySaysTheSecondWasNotRun() {
    String md =
        Report.render(
            rows(
                "{\"ts\":\"2026-10-22T10:00:00-03:00\",\"job\":\"S4\",\"endpoint\":\"/p\",\"status\":200,"
                    + "\"latencyMs\":5,\"headers\":{},\"pass\":1,\"page\":1,\"totalRegistros\":7,"
                    + "\"ids\":[\"a\"]}"),
            DAY);

    assertThat(md).contains("Segunda passada não executada");
  }

  @Test
  void s4StableTotalIsReportedAsStable() {
    String line =
        "{\"ts\":\"2026-10-22T10:00:00-03:00\",\"job\":\"S4\",\"endpoint\":\"/p\",\"status\":200,"
            + "\"latencyMs\":5,\"headers\":{},\"pass\":%d,\"page\":1,\"totalRegistros\":7,\"ids\":[\"a\"]}";

    String md = Report.render(rows(line.formatted(1), line.formatted(2)), DAY);

    assertThat(md).contains("totalRegistros: passada 1 = 7, passada 2 = 7 (estável)");
  }

  @Test
  void s5ReportsWindowsFieldMatchOffsetsAndReappearance() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md)
        .contains("| 1 | W1 | 2026-10-22T08:00:00-03:00 a 2026-10-22T09:00:00-03:00 | 1 | 3 |")
        .contains("| 2 | W1 | 2026-10-22T08:00:00-03:00 a 2026-10-22T09:00:00-03:00 | 1 | 3 |")
        .contains("| 2 | W2 | 2026-10-22T09:00:00-03:00 a 2026-10-22T10:00:00-03:00 | 1 | 1 |")
        .contains("- dataAtualizacao: 4 de 6 (66,7%)")
        .contains("- dataAtualizacaoGlobal: 7 de 7 (100,0%)")
        .contains("Conclusão: `atualizacao` filtra por `dataAtualizacaoGlobal`")
        .contains("Timestamps com offset: 0 de 13 (sem offset: horário local do portal)")
        .contains(
            "Passada 1: 3 itens; passada 2: 3 itens; em ambas: 2; alterados (hash diferente): 1;"
                + " novos na passada 2: 1; ausentes na passada 2: 1.");
  }

  @Test
  void s5ConcludesUpdatedFieldWhenOnlyItMatchesTheWindow() {
    String md =
        Report.render(
            rows(s5(1, "W1", IN, entry("2026-10-22T09:00:00", "2026-10-25T09:00:00"))), DAY);

    assertThat(md)
        .contains("Conclusão: `atualizacao` filtra por `dataAtualizacao`")
        .contains("Segunda passada não executada");
  }

  @Test
  void s5IsInconclusiveWhenBothOrNeitherFieldMatch() {
    String both =
        Report.render(
            rows(s5(1, "W1", IN, entry("2026-10-22T09:00:00", "2026-10-22T09:00:00"))), DAY);
    String neither =
        Report.render(
            rows(s5(1, "W1", IN, entry("2026-10-20T09:00:00", "2026-10-25T09:00:00"))), DAY);
    String empty = Report.render(rows(s5(1, "W1", IN, "")), DAY);

    assertThat(both).contains("Conclusão: inconclusivo");
    assertThat(neither).contains("Conclusão: inconclusivo");
    assertThat(empty).contains("Conclusão: inconclusivo");
  }

  @Test
  void s5DetectsTimestampsThatCarryAnOffset() {
    String md =
        Report.render(
            rows(s5(1, "W1", IN, entry("2026-10-22T09:00:00-03:00", "2026-10-22T12:00:00Z"))), DAY);

    assertThat(md)
        .contains("Timestamps com offset: 2 de 2 (com offset: converter para o fuso do portal)");
  }

  @Test
  void s6TableAndFlagsForAuthenticationAndCpfLikeFields() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md)
        .contains("| /api/consulta/v1/contratos | 200 | 800 | 10 | 2 |  |")
        .contains(
            "| /api/consulta/v1/atas | -1 | 30000 | 0 | 0 | java.net.http.HttpTimeoutException:"
                + " request timed out |")
        .contains("Exigem autenticação ou bloquearam (HTTP 401 ou 403): ")
        .contains("/api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/itens")
        .contains("Campos tipo CPF encontrados em: /api/consulta/v1/contratos")
        .contains("amostra: 10 itens em 2 respostas 2xx")
        .contains("Sem dados (falha de transporte, status -1): /api/consulta/v1/atas");
  }

  @Test
  void s6WithoutAuthOrCpfSaysSo() {
    String md =
        Report.render(
            rows(
                "{\"ts\":\"2026-10-22T10:00:00-03:00\",\"job\":\"S6\",\"endpoint\":\"/e\",\"status\":200,"
                    + "\"latencyMs\":5,\"headers\":{},\"items\":3,\"cpfLike\":0}"),
            DAY);

    assertThat(md)
        .contains("Todos responderam sem autenticação")
        .contains("Nenhum campo tipo CPF encontrado")
        .contains("amostra: 3 itens em 1 respostas 2xx")
        .doesNotContain("falha de transporte");
  }

  @Test
  void s7SumsPerDayAndEstimatesRowsPerYear() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md)
        .contains("| 20260922 | 100 | 150 |")
        .contains("| 20260923 | 300 | 370 |")
        .contains("Modalidade 6: 73000 linhas por ano (média diária de 200,0 vezes 365)")
        .contains(
            "Todas as modalidades lidas: 94900 linhas por ano (média diária de 260,0 vezes 365)");
  }

  @Test
  void s8SizesResponsesPerEndpointAndPerItem() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md)
        .contains(
            "| /api/consulta/v1/contratacoes/publicacao | 6 | 1700 | 2000 | 2000 | 2000 | 92,7 |")
        .contains(
            "| /api/consulta/v1/contratacoes/atualizacao | 1 | 500 | 500 | 500 | 500 | 50,0 |");
  }

  @Test
  void s9ShowsTheConnectionErrorOrTheServedText() throws IOException {
    String failed = Report.render(fixture(), DAY);
    String served =
        Report.render(
            rows(
                "{\"ts\":\"2026-10-22T10:00:00-03:00\",\"job\":\"S9\",\"endpoint\":\"/robots.txt\","
                    + "\"status\":200,\"latencyMs\":5,\"headers\":{},\"robotsTxt\":\"User-agent: *\"}"),
            DAY);

    assertThat(failed).contains("Erro de conexão: java.net.http.HttpClient: Connection reset");
    assertThat(failed).doesNotContain("```");
    assertThat(served).contains("HTTP 200").contains("```\nUser-agent: *\n```");
  }

  @Test
  void s10ListsPresenceAndNullRatePerEndpoint() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md)
        .contains("### /api/consulta/v1/contratacoes/publicacao")
        .contains("| a | 100 | 0 | 0,0% |")
        .contains("| b | 60 | 40 | 40,0% |")
        .contains("| o.x | 20 | 80 | 80,0% |")
        .contains("Campos opcionais (algum nulo ou ausente): 2 de 4");
  }

  @Test
  void decisionsAreComputedFromTheData() throws IOException {
    String md = Report.render(fixture(), DAY);

    assertThat(md)
        .contains("- Taxa do cliente:")
        .contains("- Intervalo de polling: manter 5 min")
        .contains("- Estratégia incremental: usar `atualizacao`")
        .contains("- Recorte: modalidade 6 com cerca de 73000 linhas por ano")
        .contains("- Fuso: ")
        .contains("- Coleta: robots.txt sem resposta");
  }

  @Test
  void decisionsStaySafeWithoutData() {
    String md = Report.render(List.of(), DAY);

    assertThat(md)
        .contains("0 requisições, 0 com erro")
        .contains("Período observado: sem dados")
        .contains("sem dados (não executado)")
        .contains("sem dados de S3: manter 1,0 req/s")
        .contains("Estratégia incremental: sem dados de S5")
        .contains("Recorte: sem dados de S7")
        .contains("Fuso: sem dados de S5")
        .contains("Camada opcional: sem dados de S6")
        .contains("Coleta: sem dados de S9");
    assertThat(md.split("Sem dados\\.", -1).length - 1).isEqualTo(9);
  }

  @Test
  void readsEveryJsonlFileInOrderSkippingPartialLinesAndOtherFiles(@TempDir Path dir)
      throws IOException {
    Files.writeString(
        dir.resolve("requests-20261022-1000.jsonl"),
        "{\"n\":1}\n{\"n\":2}\n{\"n\":3",
        StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("requests-20261022-1100.jsonl"), "{\"n\":4}\n\n{\"n\":5}\n");
    Files.writeString(dir.resolve("probe.log"), "{\"n\":99}\n");

    List<JsonNode> rows = Report.read(dir);

    assertThat(rows.stream().map(r -> r.path("n").asInt())).containsExactly(1, 2, 4, 5);
  }

  @Test
  void generateWritesTheMarkdownCreatingParentDirectories(@TempDir Path dir) throws IOException {
    Files.copy(Path.of("src/test/resources/full.jsonl"), dir.resolve("requests-1.jsonl"));
    Path target = dir.resolve("docs/benchmarks/probe/2026-10-22.md");

    Report.generate(dir, target, DAY);

    assertThat(Files.readString(target)).contains("## S7 Volume").contains("Data: 2026-10-22");
  }

  @Test
  void s9ShowsNoBodyForANon2xxAnswerSuchAsThePortalErrorPage() {
    String md =
        Report.render(
            rows(
                "{\"ts\":\"2026-10-22T10:00:00-03:00\",\"job\":\"S9\",\"endpoint\":\"/robots.txt\","
                    + "\"status\":404,\"latencyMs\":5,\"headers\":{},\"robotsTxt\":\"<html>\"}"),
            DAY);

    assertThat(md)
        .contains("- HTTP 404")
        .doesNotContain("```")
        .contains(
            "robots.txt respondeu HTTP 404; conferir o conteúdo e os termos de uso do portal");
  }

  @Test
  void s7CountsAnEmptyAnswerAsZeroRowsForThatDayAndModality() {
    String ok =
        "{\"ts\":\"2026-10-22T10:00:00-03:00\",\"job\":\"S7\",\"endpoint\":\"/p\",\"status\":200,"
            + "\"latencyMs\":5,\"headers\":{},\"totalRegistros\":%d,\"day\":\"%s\",\"modalidade\":%d}";
    String empty =
        "{\"ts\":\"2026-10-22T10:00:00-03:00\",\"job\":\"S7\",\"endpoint\":\"/p\",\"status\":204,"
            + "\"latencyMs\":5,\"headers\":{},\"day\":\"20260923\",\"modalidade\":6}";

    String broken =
        empty.replace("\"status\":204", "\"status\":500").replace("20260923", "20260924");

    String md =
        Report.render(
            rows(ok.formatted(100, "20260922", 6), empty, ok.formatted(10, "20260922", 1), broken),
            DAY);

    assertThat(md)
        .contains("| 20260923 | 0 | 0 |")
        .doesNotContain("20260924")
        .contains("Modalidade 6: 18250 linhas por ano (média diária de 50,0 vezes 365)");
  }

  @Test
  void readKeepsMemoryLowByMergingFieldStatsPerEndpointIntoTheFirstRow(@TempDir Path dir)
      throws IOException {
    String line =
        "{\"ts\":\"2026-10-22T10:00:00-03:00\",\"endpoint\":\"%s\",\"job\":\"S1\",\"items\":%d,"
            + "\"fieldStats\":%s}\n";
    Files.writeString(
        dir.resolve("requests-1.jsonl"),
        line.formatted("/e", 1, "{\"a\":{\"present\":1,\"null\":0}}")
            + line.formatted(
                "/e", 3, "{\"a\":{\"present\":2,\"null\":1},\"b\":{\"present\":0,\"null\":3}}")
            + line.formatted("/f", 5, "{\"x\":{\"present\":5,\"null\":0}}")
            + "{\"ts\":\"2026-10-22T10:00:00-03:00\",\"endpoint\":\"/e\",\"job\":\"S1\"}\n");

    List<JsonNode> rows = Report.read(dir);

    assertThat(rows.get(0).path("fieldStats").path("a").path("present").asInt()).isEqualTo(3);
    assertThat(rows.get(0).path("fieldStats").path("b").path("present").asInt()).isZero();
    assertThat(rows.get(1).has("fieldStats")).isFalse();
    assertThat(rows.get(0).path("statsItems").asInt()).isEqualTo(4);
    assertThat(rows.get(2).path("fieldStats").path("x").path("present").asInt()).isEqualTo(5);
    assertThat(rows).hasSize(4);
    assertThat(Report.render(rows, DAY)).contains("| a | 3 | 1 | 25,0% |");
  }

  @Test
  void readToleratesATruncatedMultibyteCharacterAtTheEndOfAKilledRun(@TempDir Path dir)
      throws IOException {
    byte[] killed = "{\"n\":1}\n{\"n\":2,\"t\":\"\u00e9".getBytes(StandardCharsets.UTF_8);
    Files.write(
        dir.resolve("requests-1.jsonl"), java.util.Arrays.copyOf(killed, killed.length - 1));

    assertThat(Report.read(dir).stream().map(r -> r.path("n").asInt())).containsExactly(1);
  }

  private static String line(String job, String endpoint, int status, String extra) {
    return ("{\"ts\":\"2026-10-22T10:00:00-03:00\",\"job\":\"%s\",\"endpoint\":\"%s\","
            + "\"status\":%d,\"latencyMs\":5,\"headers\":{}%s}")
        .formatted(job, endpoint, status, extra);
  }

  @Test
  void s6SaysWhichEndpointsFailedInsteadOfClaimingTheyAnsweredWithoutAuth() {
    String md =
        Report.render(
            rows(line("S6", "/ok", 200, ",\"items\":1,\"cpfLike\":0"), line("S6", "/down", -1, "")),
            DAY);

    assertThat(md)
        .contains("Nenhum HTTP 401 ou 403 entre os que responderam")
        .contains("Sem dados (falha de transporte, status -1): /down")
        .doesNotContain("Todos responderam sem autenticação");
  }

  @Test
  void s10DerivesNullsFromTheItemsReadSoPathsMissingInSomeResponsesCount() {
    String stats = ",\"items\":10,\"fieldStats\":%s";
    String md =
        Report.render(
            rows(
                line(
                    "S1",
                    "/e",
                    200,
                    stats.formatted(
                        "{\"a\":{\"present\":10,\"null\":0},\"b\":{\"present\":4,\"null\":6}}")),
                line("S1", "/e", 200, stats.formatted("{\"a\":{\"present\":10,\"null\":0}}")),
                line("S3", "/s3only", 200, stats.formatted("{\"a\":{\"present\":10,\"null\":0}}"))),
            DAY);

    assertThat(md)
        .contains("| a | 20 | 0 | 0,0% |")
        .contains("| b | 4 | 16 | 80,0% |")
        .doesNotContain("### /s3only");
  }

  @Test
  void s7ReportsHowManyOfTheCellsWereReadAndWhichAreMissing() throws IOException {
    String full = Report.render(fixture(), DAY);
    List<String> cells = new ArrayList<>();
    for (int m = 1; m <= 14; m++) {
      if (m != 5) {
        cells.add(
            line(
                "S7", "/p", 200, ",\"totalRegistros\":1,\"day\":\"20260922\",\"modalidade\":" + m));
      }
    }

    String partial = Report.render(rows(cells.toArray(String[]::new)), DAY);

    assertThat(full)
        .contains("Cobertura: 4 de 420 células lidas (30 dias x 14 modalidades), faltam 416")
        .contains("Células ausentes em dias lidos (primeiras 20): 20260922/2");
    cells.add(line("S7", "/p", 200, ",\"totalRegistros\":1,\"day\":\"20260922\",\"modalidade\":5"));
    assertThat(Report.render(rows(cells.toArray(String[]::new)), DAY))
        .contains("Cobertura: 14 de 420 células lidas")
        .contains("Nenhuma célula ausente nos dias lidos");
    assertThat(partial)
        .contains("Cobertura: 13 de 420 células lidas")
        .contains("Células ausentes em dias lidos: 20260922/5\n");
  }

  @Test
  void limitHeadersAreMatchedCaseInsensitively() {
    String md =
        Report.render(
            rows(
                "{\"ts\":\"2026-10-22T10:00:00-03:00\",\"job\":\"S1\",\"endpoint\":\"/e\",\"status\":200,"
                    + "\"latencyMs\":5,\"headers\":{\"X-Rate-Limit-Remaining\":\"1\","
                    + "\"RateLimit-Limit\":\"2\",\"Retry-After\":\"3\",\"Content-Type\":\"x\"}}"),
            DAY);

    assertThat(md)
        .contains(
            "Headers de limite presentes: RateLimit-Limit, Retry-After, X-Rate-Limit-Remaining");
  }

  @Test
  void s1ShowsDashesWhenAHourHasOnlyTimeouts() {
    String md = Report.render(rows(line("S1", "/e", -1, "")), DAY);

    assertThat(md).contains("| /e | 10 | 0 | - | - | - | 1 |");
  }

  @Test
  void pollingDecisionUsesTheObservedLatencyOfTheUpdatesEndpoint() throws IOException {
    String slow =
        line("S1", "/api/consulta/v1/contratacoes/atualizacao", 200, "")
            .replace("\"latencyMs\":5", "\"latencyMs\":40000");

    assertThat(Report.render(fixture(), DAY))
        .contains(
            "- Intervalo de polling: manter 5 min com sobreposição de 15 min (padrão do plano);")
        .contains("p95 de `atualizacao` = 200 ms, ciclo de até 10 páginas cabe no intervalo");
    assertThat(Report.render(rows(slow), DAY))
        .contains("p95 de `atualizacao` = 40000 ms: ciclo de até 10 páginas não cabe em 5 min");
    assertThat(Report.render(List.of(), DAY))
        .contains("- Intervalo de polling: sem dados de S1 para `atualizacao`");
  }

  @Test
  void backfillDecisionSizesTheNightlyWindowFromTheVolume() throws IOException {
    assertThat(Report.render(fixture(), DAY))
        .contains(
            "- Backfill: janela noturna (0h às 6h), páginas de 50, um dia por vez: cerca de 4 páginas"
                + " por dia de modalidade 6, 8 s por dia a 0,5 req/s, 2700 dias por noite");
    assertThat(Report.render(List.of(), DAY)).contains("- Backfill: sem dados de S7");
  }
}
