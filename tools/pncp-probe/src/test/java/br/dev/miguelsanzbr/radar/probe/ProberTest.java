package br.dev.miguelsanzbr.radar.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ProberTest {

  private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
  private static final Instant TEN = Instant.parse("2026-10-22T13:00:00Z");

  private record Outcome(int exit, List<ObjectNode> records) {

    List<ObjectNode> job(String job) {
      return records.stream().filter(r -> r.path("job").asString().equals(job)).toList();
    }
  }

  private static Outcome run(Instant start, String duration, Transport transport) {
    return run(start, duration, transport, false);
  }

  private static Outcome run(Instant start, String duration, Transport transport, boolean ramp) {
    FakeTime time = new FakeTime(start);
    List<ObjectNode> records = new ArrayList<>();
    Prober prober =
        new Prober(
            new Prober.Config("https://pncp.test", Duration.parse(duration), SP, ramp),
            transport,
            time,
            time,
            records::add,
            Runnable::run,
            new Random(7));
    return new Outcome(prober.run(), records);
  }

  private static Instant ts(JsonNode record) {
    return OffsetDateTime.parse(record.path("ts").asString()).toInstant();
  }

  /** Largest number of requests starting within any 1 s window; records are in time order. */
  private static int maxPerSecond(List<ObjectNode> records) {
    List<Instant> at = records.stream().map(ProberTest::ts).toList();
    int max = 0;
    int low = 0;
    for (int high = 0; high < at.size(); high++) {
      while (!at.get(low).isAfter(at.get(high).minusSeconds(1))) {
        low++;
      }
      max = Math.max(max, high - low + 1);
    }
    return max;
  }

  @Test
  void baseCadenceIsOneRequestEveryFiveSecondsAndEveryRecordIsComplete() {
    Outcome out = run(TEN, "PT30S", new FakeApi());

    assertThat(out.exit()).isZero();
    assertThat(out.records()).hasSize(7);
    for (int i = 1; i < out.records().size(); i++) {
      assertThat(Duration.between(ts(out.records().get(i - 1)), ts(out.records().get(i))))
          .isEqualTo(Duration.ofSeconds(5));
    }
    assertThat(out.records().get(0).path("ts").asString()).isEqualTo("2026-10-22T10:00:00-03:00");
    assertThat(out.records().stream().limit(5).map(r -> r.path("job").asString()))
        .containsExactly("S1", "S9", "S4", "S5", "S7");
    assertThat(Prober.BASE_INTERVAL).isEqualTo(Duration.ofSeconds(5));
    for (ObjectNode r : out.records()) {
      assertThat(r.propertyNames())
          .contains(
              "ts", "job", "endpoint", "url", "status", "latencyMs", "bytes", "headers", "error");
    }
    assertThat(maxPerSecond(out.records())).isEqualTo(1);
  }

  @Test
  void samplerRotatesTheThreeEndpointsWithTheDocumentedParameters() {
    Outcome out = run(TEN, "PT10M", new FakeApi());
    List<ObjectNode> s1 = out.job("S1");

    assertThat(s1.stream().limit(3).map(r -> r.path("endpoint").asString()))
        .containsExactly(
            "/api/consulta/v1/contratacoes/publicacao",
            "/api/consulta/v1/contratacoes/atualizacao",
            "/api/consulta/v1/contratacoes/proposta");
    assertThat(s1.get(0).path("url").asString())
        .isEqualTo(
            "https://pncp.test/api/consulta/v1/contratacoes/publicacao?dataInicial=20261021"
                + "&dataFinal=20261021&codigoModalidadeContratacao=6&pagina=1&tamanhoPagina=50");
    assertThat(s1.get(1).path("url").asString())
        .isEqualTo(
            "https://pncp.test/api/consulta/v1/contratacoes/atualizacao?dataInicial=20261022"
                + "&dataFinal=20261022&codigoModalidadeContratacao=6&pagina=1&tamanhoPagina=50");
    assertThat(s1.get(2).path("url").asString())
        .isEqualTo(
            "https://pncp.test/api/consulta/v1/contratacoes/proposta?dataFinal=20261121"
                + "&codigoModalidadeContratacao=6&pagina=1&tamanhoPagina=50");
    List<Integer> pages =
        s1.stream()
            .filter(r -> r.path("endpoint").asString().endsWith("publicacao"))
            .map(
                r ->
                    Integer.parseInt(
                        r.path("url").asString().replaceAll(".*pagina=(\\d+).*", "$1")))
            .toList();
    assertThat(pages.get(0)).isEqualTo(1);
    assertThat(pages.subList(1, pages.size()))
        .allMatch(p -> p >= 1 && p <= 20)
        .anyMatch(p -> p > 1);
  }

  @Test
  void samplerWindowForUpdatesIsTheLastHourAcrossMidnight() {
    Instant justAfterMidnight = Instant.parse("2026-10-22T03:30:00Z");

    Outcome out = run(justAfterMidnight, "PT30S", new FakeApi());

    assertThat(out.job("S1").get(1).path("url").asString())
        .contains("dataInicial=20261021&dataFinal=20261022");
  }

  @Test
  void storesCountsAndFieldStatsButNeverTheBody() {
    Outcome out = run(TEN, "PT4S", new FakeApi());
    ObjectNode first = out.job("S1").get(0);

    assertThat(first.path("totalRegistros").asInt()).isEqualTo(1000);
    assertThat(first.path("totalPaginas").asInt()).isEqualTo(20);
    assertThat(first.path("items").asInt()).isEqualTo(2);
    assertThat(first.path("bytes").asInt()).isPositive();
    assertThat(first.path("status").asInt()).isEqualTo(200);
    assertThat(first.path("error").isNull()).isTrue();
    assertThat(first.path("headers").path("content-type").asString()).isEqualTo("application/json");
    assertThat(first.path("fieldStats").path("informacaoComplementar").path("null").asInt())
        .isEqualTo(2);
    assertThat(first.path("fieldStats").path("orgaoEntidade.cnpj").path("present").asInt())
        .isEqualTo(2);
    assertThat(first.propertyNames()).doesNotContain("body", "data");
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 403})
  void stopsAtTheFirstRateLimitOrForbiddenAfterRecordingAllHeaders(int status) {
    FakeApi api = new FakeApi();
    Transport limited =
        url ->
            url.contains("publicacao")
                ? new Reply(status, Map.of("retry-after", List.of("30")), "", 0, 80, null)
                : api.send(url);

    Outcome out = run(TEN, "PT1H", limited);

    assertThat(out.exit()).isEqualTo(status);
    assertThat(out.records().get(out.records().size() - 1).path("status").asInt())
        .isEqualTo(status);
    assertThat(
            out.records()
                .get(out.records().size() - 1)
                .path("headers")
                .path("retry-after")
                .asString())
        .isEqualTo("30");
    assertThat(out.records().stream().filter(r -> r.path("status").asInt() == status)).hasSize(1);
  }

  @Test
  void otherServerErrorsAreRecordedAndTheRunContinues() {
    Outcome out = run(TEN, "PT20S", url -> FakeApi.json(500, "{}"));

    assertThat(out.exit()).isZero();
    assertThat(out.records()).hasSize(5);
  }

  @Test
  void timeoutsAreRecordedWithStatusMinusOneAndDoNotStop() {
    Reply timeout =
        new Reply(
            -1, Map.of(), "", 0, 30_000, "java.net.http.HttpTimeoutException: request timed out");

    Outcome out = run(TEN, "PT10S", url -> timeout);

    assertThat(out.exit()).isZero();
    assertThat(out.records()).hasSize(3);
    assertThat(out.records())
        .allSatisfy(
            r -> {
              assertThat(r.path("status").asInt()).isEqualTo(-1);
              assertThat(r.path("error").asString()).contains("HttpTimeoutException");
            });
  }

  @Test
  void robotsIsRecordedWithItsTextOrItsErrorAndNeverStopsTheRun() {
    FakeApi api = new FakeApi();
    Outcome failing = run(TEN, "PT2S", api);
    api.robots = FakeApi.json(200, "User-agent: *\n" + "x".repeat(3000));
    Outcome serving = run(TEN, "PT2S", api);

    ObjectNode failed = failing.job("S9").get(0);
    assertThat(failed.path("status").asInt()).isEqualTo(-1);
    assertThat(failed.path("error").asString()).contains("Connection reset");
    assertThat(failed.path("endpoint").asString()).isEqualTo("/robots.txt");
    assertThat(serving.job("S9").get(0).path("robotsTxt").asString())
        .startsWith("User-agent: *")
        .hasSize(2000);
    assertThat(failing.exit()).isZero();
  }

  @Test
  void deepPaginationAsksFirstTenFiftyHundredAndLastPageAndRecordsIds() {
    FakeApi api = new FakeApi();
    api.totalPages = 120;

    List<ObjectNode> s4 = run(TEN, "PT30M", api).job("S4");

    assertThat(s4.stream().map(r -> r.path("page").asInt())).containsExactly(1, 10, 50, 100, 120);
    assertThat(s4.get(0).path("url").asString())
        .isEqualTo(
            "https://pncp.test/api/consulta/v1/contratacoes/publicacao?dataInicial=20260922"
                + "&dataFinal=20261022&codigoModalidadeContratacao=6&pagina=1&tamanhoPagina=50");
    assertThat(s4.get(4).path("url").asString()).contains("pagina=120&");
    assertThat(s4.get(0).path("ids").toString())
        .isEqualTo("[\"11111111000191-1-000001/2026\",\"11111111000191-1-000002/2026\"]");
    assertThat(s4).allMatch(r -> r.path("pass").asInt() == 1);
  }

  @Test
  void deepPaginationSkipsPagesBeyondTheTotalAndDeduplicates() {
    FakeApi api = new FakeApi();
    api.totalPages = 100;
    assertThat(run(TEN, "PT30M", api).job("S4").stream().map(r -> r.path("page").asInt()))
        .containsExactly(1, 10, 50, 100);
    api.totalPages = 20;
    assertThat(run(TEN, "PT30M", api).job("S4").stream().map(r -> r.path("page").asInt()))
        .containsExactly(1, 10, 20);
    api.totalPages = 1;
    assertThat(run(TEN, "PT30M", api).job("S4").stream().map(r -> r.path("page").asInt()))
        .containsExactly(1);
    api.totalPages = 0;
    assertThat(run(TEN, "PT30M", api).job("S4").stream().map(r -> r.path("page").asInt()))
        .containsExactly(1);
  }

  @Test
  void secondPassOfDeepPaginationAndUpdatesRunAnHourLater() {
    Outcome out = run(TEN, "PT90M", new FakeApi());
    Instant hourLater = TEN.plus(Duration.ofHours(1));

    List<ObjectNode> second =
        out.job("S4").stream().filter(r -> r.path("pass").asInt() == 2).toList();
    assertThat(second).isNotEmpty().allMatch(r -> !ts(r).isBefore(hourLater));
    assertThat(second.get(0).path("page").asInt()).isEqualTo(1);
    assertThat(out.job("S4").stream().filter(r -> r.path("pass").asInt() == 1))
        .allMatch(r -> ts(r).isBefore(hourLater));
    assertThat(out.job("S5").stream().filter(r -> r.path("pass").asInt() == 2))
        .isNotEmpty()
        .allMatch(r -> !ts(r).isBefore(hourLater));
  }

  @Test
  void noSecondPassWhenTheRunEndsBeforeOneHour() {
    Outcome out = run(TEN, "PT59M", new FakeApi());

    assertThat(out.job("S4")).allMatch(r -> r.path("pass").asInt() == 1);
    assertThat(out.job("S5")).allMatch(r -> r.path("pass").asInt() == 1);
  }

  @Test
  void updateSemanticsFetchesTheTwoHourWindowThenTheSameAndTheNextOneAnHourLater() {
    FakeApi api = new FakeApi();
    api.totalPages = 3;

    List<ObjectNode> s5 = run(TEN, "PT90M", api).job("S5");

    List<ObjectNode> first = s5.stream().filter(r -> r.path("pass").asInt() == 1).toList();
    assertThat(first).hasSize(3).allMatch(r -> r.path("window").asString().equals("W1"));
    assertThat(first.get(0).path("windowStart").asString()).isEqualTo("2026-10-22T08:00:00-03:00");
    assertThat(first.get(0).path("windowEnd").asString()).isEqualTo("2026-10-22T09:00:00-03:00");
    assertThat(first.get(0).path("url").asString())
        .isEqualTo(
            "https://pncp.test/api/consulta/v1/contratacoes/atualizacao?dataInicial=20261022"
                + "&dataFinal=20261022&codigoModalidadeContratacao=6&pagina=1&tamanhoPagina=50");
    assertThat(first.stream().map(r -> r.path("page").asInt())).containsExactly(1, 2, 3);
    JsonNode entry = first.get(0).path("entries").get(0);
    assertThat(entry.path("id").asString()).isEqualTo("11111111000191-1-000001/2026");
    assertThat(entry.path("dataAtualizacao").asString()).isEqualTo("2026-10-22T10:00:01");
    assertThat(entry.path("dataAtualizacaoGlobal").asString()).isEqualTo("2026-10-22T10:00:11");
    assertThat(entry.path("hash").asString()).hasSize(64).matches("[0-9a-f]+");
    List<ObjectNode> second = s5.stream().filter(r -> r.path("pass").asInt() == 2).toList();
    assertThat(second.stream().map(r -> r.path("window").asString()).distinct())
        .containsExactlyInAnyOrder("W1", "W2");
    assertThat(
            second.stream()
                .filter(r -> r.path("window").asString().equals("W2"))
                .findFirst()
                .orElseThrow()
                .path("windowStart")
                .asString())
        .isEqualTo("2026-10-22T09:00:00-03:00");
    assertThat(second).hasSize(6);
  }

  @Test
  void updateSemanticsReadsAtMostFortyPagesPerWindow() {
    FakeApi api = new FakeApi();
    api.totalPages = 500;

    List<ObjectNode> s5 = run(TEN, "PT30M", api).job("S5");

    assertThat(s5).hasSize(40);
  }

  @Test
  void volumeCoversEachOfTheLastThirtyDaysAndFourteenModalities() {
    List<ObjectNode> s7 = run(TEN, "PT2H", new FakeApi()).job("S7");

    assertThat(s7).hasSize(420);
    assertThat(s7.stream().map(r -> r.path("day").asString()).distinct()).hasSize(30);
    assertThat(s7.stream().map(r -> r.path("modalidade").asInt()).distinct())
        .hasSize(14)
        .contains(1, 14);
    assertThat(s7.stream().map(r -> r.path("day").asString()).min(String::compareTo))
        .contains("20260922");
    assertThat(s7.stream().map(r -> r.path("day").asString()).max(String::compareTo))
        .contains("20261021");
    assertThat(s7.get(0).path("url").asString())
        .isEqualTo(
            "https://pncp.test/api/consulta/v1/contratacoes/publicacao?dataInicial=20260922"
                + "&dataFinal=20260922&codigoModalidadeContratacao=1&pagina=1&tamanhoPagina=10");
  }

  @Test
  void optionalEndpointsRunOnceOnceAnIdFromEarlierResponsesIsKnown() {
    List<ObjectNode> s6 = run(TEN, "PT10M", new FakeApi()).job("S6");

    assertThat(s6.stream().map(r -> r.path("endpoint").asString()))
        .containsExactlyInAnyOrder(
            "/api/consulta/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}",
            "/api/consulta/v1/contratos",
            "/api/consulta/v1/atas",
            "/api/pncp/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}/itens");
    assertThat(s6.stream().map(r -> r.path("url").asString()))
        .contains(
            "https://pncp.test/api/consulta/v1/orgaos/11111111000191/compras/2026/1",
            "https://pncp.test/api/pncp/v1/orgaos/11111111000191/compras/2026/1/itens",
            "https://pncp.test/api/consulta/v1/contratos?dataInicial=20261021&dataFinal=20261021"
                + "&pagina=1&tamanhoPagina=10",
            "https://pncp.test/api/consulta/v1/atas?dataInicial=20261021&dataFinal=20261021"
                + "&pagina=1&tamanhoPagina=10");
    Map<String, Integer> cpf =
        s6.stream()
            .collect(
                Collectors.toMap(
                    r -> r.path("endpoint").asString(), r -> r.path("cpfLike").asInt()));
    assertThat(cpf.get("/api/consulta/v1/contratos")).isEqualTo(1);
    assertThat(cpf.get("/api/consulta/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}")).isEqualTo(1);
    assertThat(cpf.get("/api/consulta/v1/atas")).isZero();
    assertThat(
            s6.stream()
                .filter(r -> r.path("endpoint").asString().endsWith("itens"))
                .findFirst()
                .orElseThrow()
                .path("items")
                .asInt())
        .isEqualTo(2);
  }

  @Test
  void optionalEndpointsAreSkippedWhenNoIdWasEverSeen() {
    FakeApi api = new FakeApi();
    api.totalPages = 0;

    assertThat(run(TEN, "PT10M", api).job("S6")).isEmpty();
  }

  @Test
  void rampRunsOnceAtThreeAmLocalInEightStepsAndNeverAboveFourPerSecond() {
    Instant start = Instant.parse("2026-10-22T05:59:00Z");

    Outcome out = run(start, "PT4M", new FakeApi(), true);
    List<ObjectNode> s3 = out.job("S3");

    assertThat(s3).hasSize(136);
    assertThat(s3.stream().map(r -> r.path("step").asInt()).distinct())
        .containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    assertThat(s3.stream().filter(r -> r.path("step").asInt() == 7)).hasSize(30);
    assertThat(s3.stream().filter(r -> r.path("rate").asDouble() == 0.5)).hasSize(4);
    assertThat(s3.get(0).path("url").asString())
        .isEqualTo(
            "https://pncp.test/api/consulta/v1/contratacoes/publicacao?dataInicial=20261021"
                + "&dataFinal=20261021&codigoModalidadeContratacao=6&pagina=1&tamanhoPagina=10");
    assertThat(ts(s3.get(0)))
        .isBetween(Instant.parse("2026-10-22T06:00:00Z"), Instant.parse("2026-10-22T06:00:02Z"));
    assertThat(maxPerSecond(out.records())).isLessThanOrEqualTo(4);
    assertThat(maxPerSecond(s3)).isEqualTo(4);
    assertThat(out.exit()).isZero();
  }

  @Test
  void theRampIsOffByDefaultSoTheRateNeverRisesAboveTheBaseInterval() {
    Outcome out = run(Instant.parse("2026-10-22T05:59:00Z"), "PT4M", new FakeApi());

    assertThat(out.job("S3")).isEmpty();
    assertThat(maxPerSecond(out.records())).isEqualTo(1);
    assertThat(out.records()).hasSize(49);
  }

  @Test
  void rampStartsOnTimeWhenTheRunStartsExactlyAtThreeAm() {
    Outcome out = run(Instant.parse("2026-10-22T06:00:00Z"), "PT2M", new FakeApi(), true);

    assertThat(out.job("S3")).hasSize(136);
  }

  @Test
  void rampIsNotExecutedWhenTheNextThreeAmIsBeyondTheDuration() {
    Outcome out = run(Instant.parse("2026-10-22T07:00:00Z"), "PT10H", new FakeApi(), true);

    assertThat(out.job("S3")).isEmpty();
  }

  @Test
  void rampWaitsForTheNextDayAndRunsOnlyOnceInA24HourRun() {
    Instant start = Instant.parse("2026-10-22T07:00:00Z");

    Outcome out = run(start, "PT24H", new FakeApi(), true);
    List<ObjectNode> s3 = out.job("S3");

    assertThat(s3).hasSize(136);
    assertThat(ts(s3.get(0)))
        .isBetween(Instant.parse("2026-10-23T06:00:00Z"), Instant.parse("2026-10-23T06:00:02Z"));
    assertThat(maxPerSecond(out.records())).isLessThanOrEqualTo(4);
    assertThat(out.job("S4").stream().filter(r -> r.path("pass").asInt() == 2)).isNotEmpty();
    assertThat(s3.get(0).path("url").asString())
        .contains("dataInicial=20261022&dataFinal=20261022");
  }

  @Test
  void rampStopsAtTheFirstRateLimitInTheMiddleOfTheRun() {
    FakeTime time = new FakeTime(Instant.parse("2026-10-22T05:59:30Z"));
    FakeApi api = new FakeApi();
    Instant limitAt = Instant.parse("2026-10-22T06:00:10Z");
    Transport limited =
        url -> time.instant().isBefore(limitAt) ? api.send(url) : FakeApi.json(429, "{}");
    List<ObjectNode> records = new ArrayList<>();

    int exit =
        new Prober(
                new Prober.Config("https://pncp.test", Duration.ofMinutes(5), SP, true),
                limited,
                time,
                time,
                records::add,
                Runnable::run,
                new Random(7))
            .run();

    List<ObjectNode> s3 =
        records.stream().filter(r -> r.path("job").asString().equals("S3")).toList();
    assertThat(exit).isEqualTo(429);
    assertThat(s3).isNotEmpty().hasSizeLessThan(136);
    assertThat(s3.get(s3.size() - 1).path("status").asInt()).isEqualTo(429);
    assertThat(records.get(records.size() - 1)).isSameAs(s3.get(s3.size() - 1));
  }

  @Test
  void countsRequests() {
    FakeTime time = new FakeTime(TEN);
    Prober prober =
        new Prober(
            new Prober.Config("https://pncp.test", Duration.ofSeconds(10), SP),
            new FakeApi(),
            time,
            time,
            r -> {},
            Runnable::run,
            new Random(1));

    prober.run();

    assertThat(prober.requests()).isEqualTo(3);
  }

  @Test
  void windowsUseTheStartInstantTruncatedToMilliseconds() {
    Instant start = Instant.parse("2026-10-22T13:00:00.123456789Z");

    List<ObjectNode> s5 = run(start, "PT20S", new FakeApi()).job("S5");

    assertThat(s5.get(0).path("windowStart").asString()).isEqualTo("2026-10-22T08:00:00.123-03:00");
    assertThat(s5.get(0).path("windowEnd").asString()).isEqualTo("2026-10-22T09:00:00.123-03:00");
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 403})
  void robotsAnswerIsRecordedButNeverStopsTheRun(int status) {
    FakeApi api = new FakeApi();
    api.robots = FakeApi.json(status, "");

    Outcome out = run(TEN, "PT20S", api);

    assertThat(out.exit()).isZero();
    assertThat(out.job("S9").get(0).path("status").asInt()).isEqualTo(status);
    assertThat(out.records()).hasSize(5);
  }

  @Test
  void secondPassPageOneOfDeepPaginationIsRetriedOnceWhenItFails() {
    FakeApi api = new FakeApi();
    int[] calls = {0};
    Transport failing =
        url -> {
          boolean deepPageOne =
              url.contains("dataInicial=20260922&dataFinal=20261022") && url.contains("pagina=1&");
          return deepPageOne && ++calls[0] >= 2 ? FakeApi.json(503, "{}") : api.send(url);
        };

    List<ObjectNode> s4 = run(TEN, "PT90M", failing).job("S4");

    List<ObjectNode> pass2 = s4.stream().filter(r -> r.path("pass").asInt() == 2).toList();
    assertThat(pass2).hasSize(2);
    assertThat(pass2.stream().map(r -> r.path("status").asInt())).containsExactly(503, 503);
  }

  @Test
  void secondPassPageOneOfUpdatesIsRetriedAndThenReadsTheRemainingPages() {
    FakeApi api = new FakeApi();
    api.totalPages = 3;
    Instant start = Instant.parse("2026-10-22T03:30:00Z");
    boolean[] failed = {false};
    FakeTime[] clock = new FakeTime[1];
    Transport failOnce =
        url -> {
          boolean w1 =
              url.contains("atualizacao?dataInicial=20261021&dataFinal=20261021")
                  && url.contains("pagina=1&");
          if (w1 && !failed[0] && !clock[0].instant().isBefore(start.plus(Duration.ofHours(1)))) {
            failed[0] = true;
            return FakeApi.json(500, "{}");
          }
          return api.send(url);
        };
    clock[0] = new FakeTime(start);
    List<ObjectNode> records = new ArrayList<>();

    new Prober(
            new Prober.Config("https://pncp.test", Duration.ofMinutes(90), SP),
            failOnce,
            clock[0],
            clock[0],
            records::add,
            Runnable::run,
            new Random(7))
        .run();

    List<ObjectNode> w1Pass2 =
        records.stream()
            .filter(
                r ->
                    r.path("job").asString().equals("S5")
                        && r.path("pass").asInt() == 2
                        && r.path("window").asString().equals("W1"))
            .toList();
    assertThat(w1Pass2.stream().map(r -> r.path("page").asInt() + ":" + r.path("status").asInt()))
        .containsExactly("1:500", "1:200", "2:200", "3:200");
  }

  @Test
  void yesterdayIsRecomputedForEachRequestAcrossMidnight() {
    Instant beforeMidnight = Instant.parse("2026-10-23T02:59:00Z");

    List<String> urls =
        run(beforeMidnight, "PT10M", new FakeApi()).job("S1").stream()
            .filter(r -> r.path("endpoint").asString().endsWith("publicacao"))
            .map(r -> r.path("url").asString())
            .toList();

    assertThat(urls.get(0)).contains("dataInicial=20261021&dataFinal=20261021");
    assertThat(urls.get(urls.size() - 1)).contains("dataInicial=20261022&dataFinal=20261022");
  }
}
