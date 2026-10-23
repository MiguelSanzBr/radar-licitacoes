package br.dev.miguelsanzbr.radar.probe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Builds the Markdown report (pt-BR) from the recorded JSONL, one section per measurement. */
enum Report {
  ; // no constants: a holder of static methods that cannot be instantiated

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Locale PT = Locale.of("pt", "BR");
  private static final String NO_DATA = "Sem dados.\n\n";
  private static final String UPDATES = "/api/consulta/v1/contratacoes/atualizacao";
  private static final Set<Integer> LIMIT_STATUS = Set.of(429, 403);
  private static final Set<Integer> AUTH_STATUS = Set.of(401, 403);
  private static final List<String> UPDATE_FIELDS =
      List.of("dataAtualizacao", "dataAtualizacaoGlobal");

  /**
   * Reads every JSONL file. A 24 h run holds tens of thousands of field-stat maps, so the stats of
   * each endpoint are summed into the first row that has them and dropped from the others.
   */
  static List<JsonNode> read(Path dir) throws IOException {
    List<Path> files;
    try (Stream<Path> listing = Files.list(dir)) {
      files = listing.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList();
    }
    List<JsonNode> rows = new ArrayList<>();
    Map<String, JsonNode> statsHolder = new HashMap<>();
    for (Path file : files) {
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          try {
            JsonNode row = JSON.readTree(line);
            if (row.isObject()) {
              rows.add(row);
              mergeFieldStats(statsHolder, (ObjectNode) row);
            }
          } catch (JacksonException partial) {
            // a run killed mid-write leaves a truncated last line: skip it
          }
        }
      }
    }
    return rows;
  }

  private static void mergeFieldStats(Map<String, JsonNode> holders, ObjectNode row) {
    JsonNode stats = row.path("fieldStats");
    if (stats.isObject()) {
      JsonNode holder = holders.putIfAbsent(text(row, "job") + " " + text(row, "endpoint"), row);
      if (holder == null) {
        row.put("statsItems", row.path("items").asLong());
      } else {
        mergeInto((ObjectNode) holder, row, stats);
      }
    }
  }

  /** Sums the stats and the items they were taken from into the holder row. */
  private static void mergeInto(ObjectNode holder, ObjectNode row, JsonNode stats) {
    ObjectNode total = (ObjectNode) holder.path("fieldStats");
    for (Map.Entry<String, JsonNode> field : stats.properties()) {
      ObjectNode cell =
          total.has(field.getKey())
              ? (ObjectNode) total.get(field.getKey())
              : total.putObject(field.getKey());
      cell.put(
          "present", cell.path("present").asLong() + field.getValue().path("present").asLong());
    }
    holder.put("statsItems", holder.path("statsItems").asLong() + row.path("items").asLong());
    row.remove("fieldStats");
  }

  static void generate(Path in, Path to, LocalDate day) throws IOException {
    Files.createDirectories(Objects.requireNonNull(to.toAbsolutePath().getParent()));
    Files.writeString(to, render(read(in), day), StandardCharsets.UTF_8);
  }

  static String render(List<JsonNode> rows, LocalDate day) {
    return String.join(
        "",
        header(rows, day),
        s1(rows),
        s2(rows),
        s3(rows),
        s4(rows),
        s5(rows),
        s6(rows),
        s7(rows),
        s8(rows),
        s9(rows),
        s10(rows),
        decisions(rows));
  }

  // ---- helpers ----

  private static String text(JsonNode node, String key) {
    JsonNode value = node.path(key);
    return value.isString() ? value.stringValue() : "";
  }

  private static List<JsonNode> job(List<JsonNode> rows, String... jobs) {
    Set<String> wanted = Set.of(jobs);
    return rows.stream().filter(r -> wanted.contains(text(r, "job"))).toList();
  }

  private static int status(JsonNode row) {
    return row.path("status").asInt();
  }

  private static boolean failed(JsonNode row) {
    return status(row) < 0 || status(row) >= 400;
  }

  private static boolean success(JsonNode row) {
    return status(row) / 100 == 2;
  }

  private static String hour(JsonNode row) {
    return "%02d".formatted(OffsetDateTime.parse(text(row, "ts")).getHour());
  }

  private static String num(double value) {
    return String.format(PT, "%.1f", value);
  }

  private static String pct(double part, double whole) {
    return num(100.0 * part / Math.max(1.0, whole)) + "%";
  }

  private static List<Object> latencyRow(List<Object> lead, List<JsonNode> list) {
    List<JsonNode> answered = list.stream().filter(r -> status(r) > 0).toList();
    List<Object> row = new ArrayList<>(lead);
    row.add(answered.size());
    for (int p : List.of(50, 95, 99)) {
      row.add(answered.isEmpty() ? "-" : Stats.percentile(latencies(answered), p));
    }
    row.add(list.stream().filter(r -> status(r) < 0).count());
    return row;
  }

  private static String table(List<String> head, List<List<Object>> body) {
    StringBuilder out =
        new StringBuilder(
            "| " + String.join(" | ", head) + " |\n|" + "---|".repeat(head.size()) + "\n");
    for (List<Object> row : body) {
      out.append("| ")
          .append(row.stream().map(String::valueOf).collect(Collectors.joining(" | ")))
          .append(" |\n");
    }
    return out.append("\n").toString();
  }

  private static String section(String title, Collection<?> data, Supplier<String> body) {
    return "## " + title + "\n\n" + (data.isEmpty() ? NO_DATA : body.get());
  }

  private static <K extends Comparable<K>> Map<K, List<JsonNode>> group(
      Collection<JsonNode> rows, Function<JsonNode, K> key) {
    return rows.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.toList()));
  }

  /** One row per cell, the latest by the timestamp of the record: overlapping runs repeat cells. */
  private static <K extends Comparable<K>> Map<K, JsonNode> latest(
      Collection<JsonNode> rows, Function<JsonNode, K> cell) {
    Map<K, JsonNode> out = new TreeMap<>();
    rows.forEach(r -> out.merge(cell.apply(r), r, (a, b) -> at(b).isAfter(at(a)) ? b : a));
    return out;
  }

  private static Instant at(JsonNode row) {
    return OffsetDateTime.parse(text(row, "ts")).toInstant();
  }

  private static List<Long> latencies(Collection<JsonNode> rows) {
    return rows.stream().map(r -> r.path("latencyMs").asLong()).toList();
  }

  // ---- header and S1, S2 ----

  private static String header(List<JsonNode> rows, LocalDate day) {
    Comparator<JsonNode> byTime =
        Comparator.comparing(r -> OffsetDateTime.parse(text(r, "ts")).toInstant());
    String last = rows.stream().max(byTime).map(r -> text(r, "ts")).orElse("");
    String period =
        rows.stream().min(byTime).map(r -> text(r, "ts") + " a " + last).orElse("sem dados");
    return ("# Relatório da sonda PNCP%n%nData: %s%nPeríodo observado: %s%n"
            + "Volume: %d requisições, %d com erro%n%n")
        .formatted(day, period, rows.size(), rows.stream().filter(Report::failed).count());
  }

  private static String s1(List<JsonNode> rows) {
    List<JsonNode> s1 = job(rows, "S1");
    return section(
        "S1 Latência por endpoint e hora do dia",
        s1,
        () -> {
          Map<String, Map<String, List<JsonNode>>> byEndpoint = new TreeMap<>();
          for (JsonNode r : s1) {
            for (String key : List.of(hour(r), "todas")) {
              byEndpoint
                  .computeIfAbsent(text(r, "endpoint"), k -> new TreeMap<>())
                  .computeIfAbsent(key, k -> new ArrayList<>())
                  .add(r);
            }
          }
          List<List<Object>> body = new ArrayList<>();
          byEndpoint.forEach(
              (endpoint, byHour) ->
                  byHour.forEach(
                      (hour, list) -> body.add(latencyRow(List.of(endpoint, hour), list))));
          return "Latências em ms, percentis pelo método nearest-rank, só respostas HTTP; timeouts"
              + " (status -1) contados à parte; hora no fuso do registro.\n\n"
              + table(List.of("Endpoint", "Hora", "n", "p50", "p95", "p99", "Timeouts"), body);
        });
  }

  private static String s2(List<JsonNode> rows) {
    return section(
        "S2 Erros e headers de limite",
        rows,
        () -> {
          Map<Integer, List<JsonNode>> byStatus = group(rows, Report::status);
          List<List<Object>> statuses = new ArrayList<>();
          byStatus.forEach((status, list) -> statuses.add(List.of(status, list.size())));
          List<List<Object>> hours = new ArrayList<>();
          group(rows, Report::hour)
              .forEach(
                  (hour, list) -> {
                    long errors = list.stream().filter(Report::failed).count();
                    hours.add(List.of(hour, list.size(), errors, pct(errors, list.size())));
                  });
          Map<String, Integer> headers = new TreeMap<>();
          rows.forEach(
              r ->
                  r.path("headers")
                      .propertyNames()
                      .forEach(n -> headers.merge(n, 1, Integer::sum)));
          List<List<Object>> names = new ArrayList<>();
          headers.forEach((name, n) -> names.add(List.of(name, n)));
          List<String> limit =
              headers.keySet().stream()
                  .filter(n -> n.matches("(?i)(x-)?rate-?limit.*|retry-after"))
                  .toList();
          return table(List.of("Status", "Requisições"), statuses)
              + table(List.of("Hora", "Requisições", "Erros", "Taxa de erro"), hours)
              + table(List.of("Header", "Respostas"), names)
              + "Headers de limite presentes: "
              + (limit.isEmpty() ? "nenhum" : String.join(", ", limit))
              + "\n\n";
        });
  }

  // ---- S3 ----

  private static Map<Integer, List<JsonNode>> steps(List<JsonNode> s3) {
    return group(s3, r -> r.path("step").asInt());
  }

  private static String s3(List<JsonNode> rows) {
    List<JsonNode> s3 = job(rows, "S3");
    if (s3.isEmpty()) {
      return "## S3 Rampa de taxa\n\nRampa: sem dados (não executado). Ela roda uma vez, às 03:00 de"
          + " America/Sao_Paulo, e só se esse horário cair dentro da duração.\n\n";
    }
    List<List<Object>> body = new ArrayList<>();
    steps(s3)
        .forEach(
            (step, list) ->
                body.add(
                    List.of(
                        step,
                        num(list.get(0).path("rate").asDouble()),
                        list.size(),
                        list.stream().filter(Report::success).count(),
                        list.stream().filter(Report::rateError).count(),
                        list.stream().filter(r -> status(r) < 0).count(),
                        Stats.percentile(latencies(list), 50))));
    String stop =
        s3.stream()
            .filter(r -> LIMIT_STATUS.contains(status(r)))
            .findFirst()
            .map(
                r ->
                    "Parou no primeiro 429/403: degrau %d, HTTP %d"
                        .formatted(r.path("step").asInt(), status(r)))
            .orElse("Nenhum 429 ou 403 na rampa");
    return "## S3 Rampa de taxa\n\n"
        + table(
            List.of(
                "Degrau",
                "Taxa alvo (req/s)",
                "Requisições",
                "Sucessos",
                "Erros de taxa (429, 403, 5xx)",
                "Timeouts",
                "p50 (ms)"),
            body)
        + stop
        + ".\n\nTimeouts por degrau: "
        + timeoutsText(steps(s3))
        + ".\n\n";
  }

  private static boolean rateError(JsonNode row) {
    return LIMIT_STATUS.contains(status(row)) || status(row) / 100 == 5;
  }

  private static String timeoutsText(Map<Integer, List<JsonNode>> steps) {
    List<String> perStep = new ArrayList<>();
    steps.forEach(
        (step, list) -> {
          long timeouts = list.stream().filter(r -> status(r) < 0).count();
          if (timeouts > 0) {
            perStep.add("degrau %d: %d".formatted(step, timeouts));
          }
        });
    return perStep.isEmpty() ? "nenhum" : String.join(", ", perStep);
  }

  /** Taxa em vigor numa linha: a do degrau da rampa, ou a base da sonda fora dela. */
  private static double rateInEffect(JsonNode row) {
    return row.has("rate") ? row.path("rate").asDouble() : 1000.0 / Prober.BASE_INTERVAL.toMillis();
  }

  /** Um 429 ou 403 limita a sugestão a metade da taxa em vigor, arredondada para baixo a 0,1. */
  private static String blockedText(double blocked, OptionalDouble highestOk) {
    double cap = blocked / 2;
    String ok = "";
    if (highestOk.isPresent()) {
      cap = Math.min(cap, highestOk.getAsDouble() / 2);
      ok = "; maior taxa sem erro %s req/s".formatted(num(highestOk.getAsDouble()));
    }
    return "houve bloqueio (HTTP 429 ou 403) a %s req/s%s; usar %s req/s"
            .formatted(num(blocked), ok, num(Math.floor(cap * 10) / 10))
        + " (no máximo metade da taxa do bloqueio, arredondado para baixo)";
  }

  private static String rateDecision(List<JsonNode> rows) {
    List<JsonNode> s3 = job(rows, "S3");
    OptionalDouble blocked =
        rows.stream()
            .filter(r -> LIMIT_STATUS.contains(status(r)))
            .mapToDouble(Report::rateInEffect)
            .min();
    if (s3.isEmpty()) {
      return blocked.isPresent()
          ? blockedText(blocked.getAsDouble(), OptionalDouble.empty())
          : "sem dados de S3: manter " + num(1.0) + " req/s (padrão do plano)";
    }
    Map<Integer, List<JsonNode>> byStep = steps(s3);
    Collection<List<JsonNode>> steps = byStep.values();
    OptionalDouble highestOk =
        steps.stream()
            .filter(list -> list.stream().noneMatch(Report::rateError))
            .mapToDouble(list -> list.get(0).path("rate").asDouble())
            .max();
    String timeouts = "; timeouts por degrau: " + timeoutsText(byStep);
    if (blocked.isPresent()) {
      return blockedText(blocked.getAsDouble(), highestOk) + timeouts;
    }
    if (steps.stream().allMatch(list -> list.stream().noneMatch(Report::rateError))) {
      return "Nenhum erro de taxa até 4 req/s: usar "
          + num(1.0)
          + " req/s (padrão do plano)"
          + timeouts;
    }
    return (highestOk.isPresent()
            ? "houve erro de taxa na rampa, maior taxa sem erro %s req/s, usar %s req/s (50%%)"
                .formatted(num(highestOk.getAsDouble()), num(highestOk.getAsDouble() / 2))
            : "Erro de taxa já no primeiro degrau: manter "
                + num(0.5)
                + " req/s (base da sonda) e investigar")
        + timeouts;
  }

  // ---- S4 ----

  private static String s4(List<JsonNode> rows) {
    List<JsonNode> s4 = job(rows, "S4");
    return section(
        "S4 Paginação profunda",
        s4,
        () -> {
          List<List<Object>> body = new ArrayList<>();
          s4.forEach(
              r ->
                  body.add(
                      List.of(
                          r.path("pass").asInt(),
                          r.path("page").asInt(),
                          status(r),
                          r.path("latencyMs").asLong(),
                          r.path("totalRegistros").asLong(),
                          r.path("ids").size())));
          List<JsonNode> answered = s4.stream().filter(Report::success).toList();
          Map<Integer, Long> totals = new TreeMap<>();
          latest(answered, r -> r.path("pass").asInt())
              .forEach((pass, r) -> totals.put(pass, r.path("totalRegistros").asLong()));
          Map<Integer, Map<Integer, List<String>>> ids = new TreeMap<>();
          latest(answered, r -> r.path("pass").asInt() + "/" + r.path("page").asInt())
              .values()
              .forEach(
                  r ->
                      ids.computeIfAbsent(r.path("pass").asInt(), k -> new TreeMap<>())
                          .put(
                              r.path("page").asInt(),
                              r.path("ids").valueStream().map(JsonNode::asString).toList()));
          StringBuilder out =
              new StringBuilder(
                  "Uma observação por passada e página, a mais recente com resposta 2xx; a tabela"
                      + " lista cada requisição.\n\n"
                      + table(
                          List.of(
                              "Passada",
                              "Página",
                              "Status",
                              "Latência (ms)",
                              "totalRegistros",
                              "Itens"),
                          body));
          ids.forEach(
              (pass, pages) -> {
                List<String> all = pages.values().stream().flatMap(List::stream).toList();
                out.append(
                    "- Passada %d: %d id(s) repetido(s) entre páginas%n"
                        .formatted(pass, all.size() - all.stream().distinct().count()));
              });
          out.append("\n");
          if (totals.containsKey(1) && totals.containsKey(2)) {
            long drift = Math.abs(totals.get(2) - totals.get(1));
            out.append(
                "totalRegistros: passada 1 = %d, passada 2 = %d (%s)%n%n"
                    .formatted(
                        totals.get(1),
                        totals.get(2),
                        drift == 0 ? "estável" : "variou em " + drift));
            ids.get(1)
                .forEach(
                    (page, first) -> {
                      List<String> second = ids.get(2).getOrDefault(page, List.of());
                      out.append(
                          "- Página %d: %d ausente(s) e %d novo(s) na passada 2%n"
                              .formatted(
                                  page,
                                  first.stream().filter(id -> !second.contains(id)).count(),
                                  second.stream().filter(id -> !first.contains(id)).count()));
                    });
            return out.append("\n").toString();
          }
          return out.append("Segunda passada não executada ou sem resposta válida.\n\n").toString();
        });
  }

  // ---- S5 ----

  private enum Filter {
    GLOBAL,
    UPDATED,
    INCONCLUSIVE
  }

  private record Update(Filter filter, long withOffset) {}

  private record Seen(List<LocalDate> window, JsonNode entry) {}

  private static Optional<TemporalAccessor> stamp(String text) {
    try {
      return Optional.of(DateTimeFormatter.ISO_DATE_TIME.parse(text));
    } catch (DateTimeException notATimestamp) {
      return Optional.empty();
    }
  }

  private static List<Seen> seen(List<JsonNode> s5) {
    List<Seen> out = new ArrayList<>();
    for (JsonNode r : s5) {
      LocalDate from = OffsetDateTime.parse(text(r, "windowStart")).toLocalDate();
      LocalDate to = OffsetDateTime.parse(text(r, "windowEnd")).toLocalDate();
      List<LocalDate> window = from.datesUntil(to.plusDays(1)).toList();
      r.path("entries").forEach(e -> out.add(new Seen(window, e)));
    }
    return out;
  }

  private static List<Seen> parsed(List<Seen> seen, String field) {
    return seen.stream().filter(s -> stamp(text(s.entry(), field)).isPresent()).toList();
  }

  private static long hits(List<Seen> parsed, String field) {
    return parsed.stream()
        .filter(
            s -> s.window().contains(LocalDate.from(stamp(text(s.entry(), field)).orElseThrow())))
        .count();
  }

  private static Update update(List<JsonNode> s5) {
    List<Seen> seen = seen(s5);
    List<Seen> global = parsed(seen, "dataAtualizacaoGlobal");
    List<Seen> updated = parsed(seen, "dataAtualizacao");
    double g = (double) hits(global, "dataAtualizacaoGlobal") / Math.max(1, global.size());
    double u = (double) hits(updated, "dataAtualizacao") / Math.max(1, updated.size());
    Filter filter = Filter.INCONCLUSIVE;
    if (g >= 0.99 && g > u) {
      filter = Filter.GLOBAL;
    } else if (u >= 0.99 && u > g) {
      filter = Filter.UPDATED;
    }
    long withOffset =
        seen.stream()
            .flatMap(s -> UPDATE_FIELDS.stream().map(f -> stamp(text(s.entry(), f))))
            .flatMap(Optional::stream)
            .filter(t -> t.isSupported(ChronoField.OFFSET_SECONDS))
            .count();
    return new Update(filter, withOffset);
  }

  private static String passWindow(JsonNode row) {
    return row.path("pass").asInt() + " " + text(row, "window");
  }

  private static Map<String, String> hashes(List<JsonNode> s5, int pass) {
    Map<String, String> out = new LinkedHashMap<>();
    s5.stream()
        .filter(r -> passWindow(r).equals(pass + " W1"))
        .forEach(
            r -> r.path("entries").forEach(e -> out.putIfAbsent(text(e, "id"), text(e, "hash"))));
    return out;
  }

  private static String s5(List<JsonNode> rows) {
    List<JsonNode> s5 = job(rows, "S5");
    return section(
        "S5 Semântica da atualização",
        s5,
        () -> {
          List<List<Object>> windows = new ArrayList<>();
          group(s5, Report::passWindow)
              .forEach(
                  (key, list) ->
                      windows.add(
                          List.of(
                              list.get(0).path("pass").asInt(),
                              text(list.get(0), "window"),
                              text(list.get(0), "windowStart")
                                  + " a "
                                  + text(list.get(0), "windowEnd"),
                              list.size(),
                              list.stream().mapToInt(r -> r.path("entries").size()).sum())));
          List<Seen> seen = seen(s5);
          List<Seen> global = parsed(seen, "dataAtualizacaoGlobal");
          List<Seen> updated = parsed(seen, "dataAtualizacao");
          Update update = update(s5);
          long stamps = global.size() + updated.size();
          Map<String, String> first = hashes(s5, 1);
          Map<String, String> second = hashes(s5, 2);
          long both = first.keySet().stream().filter(second::containsKey).count();
          long changed =
              first.keySet().stream()
                  .filter(id -> second.containsKey(id) && !first.get(id).equals(second.get(id)))
                  .count();
          boolean ranSecond = s5.stream().anyMatch(r -> passWindow(r).equals("2 W1"));
          return table(
                  List.of("Passada", "Janela", "Intervalo consultado", "Páginas", "Itens"), windows)
              + "A API recebe só datas (yyyyMMdd): a janela de 1 h é aproximada ao dia inteiro.\n\n"
              + "Itens cuja data cai nas datas da janela consultada:\n\n"
              + "- dataAtualizacao: %d de %d (%s)%n"
                  .formatted(
                      hits(updated, "dataAtualizacao"),
                      updated.size(),
                      pct(hits(updated, "dataAtualizacao"), updated.size()))
              + "- dataAtualizacaoGlobal: %d de %d (%s)%n%n"
                  .formatted(
                      hits(global, "dataAtualizacaoGlobal"),
                      global.size(),
                      pct(hits(global, "dataAtualizacaoGlobal"), global.size()))
              + "Conclusão: "
              + filterText(update.filter())
              + "\n\n"
              + "Timestamps com offset: %d de %d (%s)%n%n"
                  .formatted(
                      update.withOffset(),
                      stamps,
                      update.withOffset() == 0
                          ? "sem offset: horário local do portal"
                          : "com offset: converter para o fuso do portal")
              + (ranSecond
                  ? ("Passada 1: %d itens; passada 2: %d itens; em ambas: %d; alterados (hash diferente): %d;"
                          + " novos na passada 2: %d; ausentes na passada 2: %d.%n%n")
                      .formatted(
                          first.size(),
                          second.size(),
                          both,
                          changed,
                          second.size() - both,
                          first.size() - both)
                  : "Segunda passada não executada.\n\n");
        });
  }

  private static String filterText(Filter filter) {
    return switch (filter) {
      case GLOBAL -> "`atualizacao` filtra por `dataAtualizacaoGlobal`";
      case UPDATED -> "`atualizacao` filtra por `dataAtualizacao`";
      case INCONCLUSIVE ->
          "inconclusivo (ambos os campos coincidem com a janela, ou nenhum), repetir com janelas de dias diferentes";
    };
  }

  // ---- S6 ----

  private static List<String> endpointsWhere(
      List<JsonNode> s6, java.util.function.Predicate<JsonNode> test) {
    return s6.stream().filter(test).map(r -> text(r, "endpoint")).distinct().toList();
  }

  private static String authText(List<JsonNode> s6) {
    List<String> blocked = endpointsWhere(s6, r -> AUTH_STATUS.contains(status(r)));
    List<String> failed = endpointsWhere(s6, r -> status(r) < 0);
    if (!blocked.isEmpty()) {
      return "Exigem autenticação ou bloquearam (HTTP 401 ou 403): " + String.join(", ", blocked);
    }
    return failed.isEmpty()
        ? "Todos responderam sem autenticação"
        : "Nenhum HTTP 401 ou 403 entre os que responderam";
  }

  private static String failedText(List<JsonNode> s6) {
    List<String> failed = endpointsWhere(s6, r -> status(r) < 0);
    return failed.isEmpty()
        ? ""
        : "Sem dados (falha de transporte, status -1): " + String.join(", ", failed);
  }

  private static String cpfText(List<JsonNode> s6) {
    List<String> cpf = endpointsWhere(s6, r -> r.path("cpfLike").asInt() > 0);
    List<JsonNode> answered = s6.stream().filter(Report::success).toList();
    String sample =
        " (amostra: %d itens em %d respostas 2xx)"
            .formatted(
                answered.stream().mapToLong(r -> r.path("items").asLong()).sum(), answered.size());
    return (cpf.isEmpty()
            ? "Nenhum campo tipo CPF encontrado"
            : "Campos tipo CPF encontrados em: " + String.join(", ", cpf))
        + sample;
  }

  private static String optionalSummary(List<JsonNode> s6, String separator) {
    return Stream.of(authText(s6), failedText(s6), cpfText(s6))
        .filter(text -> !text.isEmpty())
        .collect(Collectors.joining(separator));
  }

  private static String s6(List<JsonNode> rows) {
    List<JsonNode> s6 = job(rows, "S6");
    return section(
        "S6 Endpoints opcionais",
        s6,
        () -> {
          List<List<Object>> body = new ArrayList<>();
          s6.forEach(
              r ->
                  body.add(
                      List.of(
                          text(r, "endpoint"),
                          status(r),
                          r.path("latencyMs").asLong(),
                          r.path("items").asInt(),
                          r.path("cpfLike").asInt(),
                          text(r, "error"))));
          return table(
                  List.of(
                      "Endpoint", "Status", "Latência (ms)", "Itens", "Campos tipo CPF", "Erro"),
                  body)
              + optionalSummary(s6, ".\n\n")
              + ".\n\n";
        });
  }

  // ---- S7 ----

  private record Volume(Map<String, long[]> days, double meanSix, double meanAll) {}

  /** A cell (day, modalidade) is read when it has a total, or the API answered 204 (no rows). */
  private static boolean covered(JsonNode row) {
    return row.has("totalRegistros") || status(row) == 204;
  }

  private static String coverage(List<JsonNode> s7) {
    Set<String> cells = new TreeSet<>();
    s7.stream()
        .filter(Report::covered)
        .forEach(r -> cells.add(text(r, "day") + "/" + r.path("modalidade").asInt()));
    List<String> days = cells.stream().map(c -> c.substring(0, c.indexOf('/'))).distinct().toList();
    List<String> missing = new ArrayList<>();
    for (String day : days) {
      for (int modalidade = 1; modalidade <= 14; modalidade++) {
        if (!cells.contains(day + "/" + modalidade)) {
          missing.add(day + "/" + modalidade);
        }
      }
    }
    String absent =
        missing.isEmpty()
            ? "Nenhuma célula ausente nos dias lidos"
            : missing.size() > 20
                ? "Células ausentes em dias lidos (primeiras 20): "
                    + String.join(", ", missing.subList(0, 20))
                    + " ..."
                : "Células ausentes em dias lidos: " + String.join(", ", missing);
    return "Cobertura: %d de 420 células lidas (30 dias x 14 modalidades), faltam %d%n%n%s%n%n"
        .formatted(cells.size(), 420 - cells.size(), absent);
  }

  private static Volume volume(List<JsonNode> s7) {
    Map<String, long[]> days = new TreeMap<>();
    List<JsonNode> answered = s7.stream().filter(Report::success).filter(Report::covered).toList();
    latest(answered, r -> text(r, "day") + "/" + r.path("modalidade").asInt())
        .values()
        .forEach(
            r -> {
              long total = r.path("totalRegistros").asLong();
              long[] sums = days.computeIfAbsent(text(r, "day"), k -> new long[2]);
              sums[0] += r.path("modalidade").asInt() == 6 ? total : 0;
              sums[1] += total;
            });
    double n = Math.max(1, days.size());
    return new Volume(
        days,
        days.values().stream().mapToLong(s -> s[0]).sum() / n,
        days.values().stream().mapToLong(s -> s[1]).sum() / n);
  }

  private static String s7(List<JsonNode> rows) {
    List<JsonNode> s7 = job(rows, "S7");
    return section(
        "S7 Volume",
        s7,
        () -> {
          Volume volume = volume(s7);
          List<List<Object>> body = new ArrayList<>();
          volume.days().forEach((day, sums) -> body.add(List.of(day, sums[0], sums[1])));
          return "Uma observação por dia e modalidade, a mais recente com resposta 2xx entre as"
              + " execuções lidas.\n\n"
              + table(List.of("Dia", "Modalidade 6", "Todas as modalidades lidas"), body)
              + coverage(s7)
              + "Modalidade 6: %d linhas por ano (média diária de %s vezes 365)%n%n"
                  .formatted(Math.round(volume.meanSix() * 365), num(volume.meanSix()))
              + "Todas as modalidades lidas: %d linhas por ano (média diária de %s vezes 365)%n%n"
                  .formatted(Math.round(volume.meanAll() * 365), num(volume.meanAll()));
        });
  }

  // ---- S8 ----

  private static String s8(List<JsonNode> rows) {
    List<JsonNode> answered = job(rows, "S1", "S4").stream().filter(r -> status(r) == 200).toList();
    Collection<JsonNode> ok = latest(answered, r -> text(r, "url")).values();
    return section(
        "S8 Tamanho da resposta",
        ok,
        () -> {
          List<List<Object>> body = new ArrayList<>();
          group(ok, r -> text(r, "endpoint"))
              .forEach(
                  (endpoint, list) -> {
                    List<Long> bytes = list.stream().map(r -> r.path("bytes").asLong()).toList();
                    long total = bytes.stream().mapToLong(Long::longValue).sum();
                    long items = list.stream().mapToLong(r -> r.path("items").asLong()).sum();
                    body.add(
                        List.of(
                            endpoint,
                            list.size(),
                            Math.round((double) total / list.size()),
                            Stats.percentile(bytes, 50),
                            Stats.percentile(bytes, 95),
                            bytes.stream().mapToLong(Long::longValue).max().orElseThrow(),
                            num((double) total / Math.max(1, items))));
                  });
          return "Respostas HTTP 200 de páginas de 50 itens (S1 e S4), em bytes; uma observação por"
              + " URL, a mais recente.\n\n"
              + table(
                  List.of(
                      "Endpoint", "Respostas", "Média", "p50", "p95", "Máximo", "Bytes por item"),
                  body);
        });
  }

  // ---- S9 ----

  private static String s9(List<JsonNode> rows) {
    List<JsonNode> s9 = job(rows, "S9");
    return section(
        "S9 robots.txt",
        s9,
        () -> {
          StringBuilder out = new StringBuilder();
          for (JsonNode r : s9) {
            out.append("- ")
                .append(
                    status(r) > 0 ? "HTTP " + status(r) : "Erro de conexão: " + text(r, "error"))
                .append("\n");
            String robots = success(r) ? text(r, "robotsTxt") : "";
            out.append(robots.isBlank() ? "" : "\n```\n" + robots + "\n```\n");
          }
          return out.append("\n").toString();
        });
  }

  // ---- S10 ----

  private static String s10(List<JsonNode> rows) {
    // S3 pages are 10 items and would skew the rates; S3 only measures the rate limit
    List<JsonNode> withStats =
        rows.stream()
            .filter(r -> r.path("fieldStats").isObject() && !text(r, "job").equals("S3"))
            .toList();
    return section(
        "S10 Estabilidade dos campos",
        withStats,
        () -> {
          StringBuilder out = new StringBuilder();
          group(withStats, r -> text(r, "endpoint"))
              .forEach(
                  (endpoint, list) -> {
                    Map<String, Long> present = new TreeMap<>();
                    long items =
                        list.stream()
                            .mapToLong(r -> r.path("statsItems").asLong(r.path("items").asLong()))
                            .sum();
                    list.forEach(
                        r ->
                            r.path("fieldStats")
                                .properties()
                                .forEach(
                                    f ->
                                        present.merge(
                                            f.getKey(),
                                            f.getValue().path("present").asLong(),
                                            Long::sum)));
                    List<List<Object>> body = new ArrayList<>();
                    present.forEach(
                        (path, n) -> body.add(List.of(path, n, items - n, pct(items - n, items))));
                    long optional = present.values().stream().filter(n -> items - n > 0).count();
                    out.append("### ")
                        .append(endpoint)
                        .append("\n\n")
                        .append(
                            table(List.of("Campo", "Presente", "Nulo ou ausente", "% nulo"), body))
                        .append(
                            "Itens lidos: %d. Campos opcionais (algum nulo ou ausente): %d de %d%n%n"
                                .formatted(items, optional, present.size()));
                  });
          return out.toString();
        });
  }

  // ---- decisions ----

  private static String decisions(List<JsonNode> rows) {
    List<JsonNode> s1 = job(rows, "S1");
    List<JsonNode> s5 = job(rows, "S5");
    List<JsonNode> s6 = job(rows, "S6");
    List<JsonNode> s7 = job(rows, "S7");
    List<JsonNode> s9 = job(rows, "S9");
    Update update = update(s5);
    Volume volume = volume(s7);
    String incremental =
        s5.isEmpty()
            ? "sem dados de S5: manter o padrão do plano (`atualizacao` com sobreposição de janela)"
            : switch (update.filter()) {
              case GLOBAL ->
                  "usar `atualizacao` por janela de `dataAtualizacaoGlobal`, com sobreposição";
              case UPDATED ->
                  "usar `atualizacao` por janela de `dataAtualizacao`, com sobreposição";
              case INCONCLUSIVE ->
                  "inconclusivo: manter `atualizacao` com sobreposição e reavaliar com mais dados";
            };
    String zone =
        s5.isEmpty()
            ? "sem dados de S5: assumir horário local do portal (America/Sao_Paulo) e converter"
                + " explicitamente para `timestamptz`"
            : update.withOffset() == 0
                ? "timestamps sem offset: tratar como America/Sao_Paulo e gravar em `timestamptz` com"
                    + " conversão explícita"
                : "timestamps com offset: converter para UTC ao gravar em `timestamptz`";
    String cut =
        s7.isEmpty()
            ? "sem dados de S7: manter modalidade 6 e 12 meses (padrão do plano)"
            : "modalidade 6 com cerca de %d linhas por ano; todas as modalidades lidas, cerca de %d"
                .formatted(Math.round(volume.meanSix() * 365), Math.round(volume.meanAll() * 365));
    List<Long> updateLatencies =
        s1.stream()
            .filter(r -> text(r, "endpoint").equals(UPDATES) && status(r) > 0)
            .map(r -> r.path("latencyMs").asLong())
            .toList();
    long p95 = updateLatencies.isEmpty() ? 0 : Stats.percentile(updateLatencies, 95);
    String polling =
        updateLatencies.isEmpty()
            ? "sem dados de S1 para `atualizacao`: manter 5 min com sobreposição de 15 min (padrão do plano)"
            : 10 * Math.max(2000, p95) < 300_000
                ? "manter 5 min com sobreposição de 15 min (padrão do plano); p95 de `atualizacao` = %d ms,"
                        .formatted(p95)
                    + " ciclo de até 10 páginas cabe no intervalo"
                : "p95 de `atualizacao` = %d ms: ciclo de até 10 páginas não cabe em 5 min, aumentar o intervalo"
                    .formatted(p95);
    long pagesPerDay = Math.max(1, (long) Math.ceil(volume.meanSix() / 50));
    String backfill =
        s7.isEmpty()
            ? "sem dados de S7: manter janela noturna (0h às 6h), páginas de 50, um dia por vez (padrão do plano)"
            : ("janela noturna (0h às 6h), páginas de 50, um dia por vez: cerca de %d páginas por dia de"
                    + " modalidade 6, %d s por dia a 0,5 req/s, %d dias por noite")
                .formatted(
                    pagesPerDay,
                    pagesPerDay * Prober.BASE_INTERVAL.toSeconds(),
                    Duration.ofHours(6).toSeconds()
                        / (pagesPerDay * Prober.BASE_INTERVAL.toSeconds()));
    String optional =
        s6.isEmpty()
            ? "sem dados de S6: manter contratos, atas e itens fora do escopo"
            : "entram só se não exigirem autenticação nem trouxerem CPF. "
                + optionalSummary(s6, "; ");
    String collection =
        s9.isEmpty()
            ? "sem dados de S9"
            : "robots.txt "
                + (status(s9.get(0)) > 0
                    ? "respondeu HTTP "
                        + status(s9.get(0))
                        + "; conferir o conteúdo e os termos de uso do portal antes de liberar a coleta"
                    : "sem resposta ("
                        + text(s9.get(0), "error")
                        + "); registrar na ADR de etiqueta de coleta");
    return "## Decisões sugeridas\n\n"
        + "- Taxa do cliente: "
        + rateDecision(rows)
        + "\n"
        + "- Intervalo de polling: "
        + polling
        + "\n"
        + "- Estratégia incremental: "
        + incremental
        + "\n"
        + "- Recorte: "
        + cut
        + "\n"
        + "- Camada opcional: "
        + optional
        + "\n"
        + "- Backfill: "
        + backfill
        + "\n"
        + "- Fuso: "
        + zone
        + "\n"
        + "- Coleta: "
        + collection
        + "\n";
  }
}
