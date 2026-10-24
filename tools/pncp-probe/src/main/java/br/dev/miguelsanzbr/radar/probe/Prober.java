package br.dev.miguelsanzbr.radar.probe;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the measurement schedule. Every request passes through one {@link RateGate}; jobs are
 * interleaved round-robin in the base slots, and the one-off S3 ramp, only when enabled, takes over
 * the gate while it runs. Stops at the first 429 or 403, after recording it.
 */
public final class Prober {

  /** {@code ramp} adds the one-off S3 rate ramp, which pushes the rate up to 4 req/s: opt-in. */
  public record Config(String baseUrl, Duration duration, ZoneId zone, boolean ramp) {

    public Config(String baseUrl, Duration duration, ZoneId zone) {
      this(baseUrl, duration, zone, false);
    }
  }

  /** Adds the job-specific fields to a request record and may enqueue follow-up requests. */
  @FunctionalInterface
  private interface Hook {
    void accept(Reply reply, JsonNode body, ObjectNode out);
  }

  private record Req(String job, String endpoint, String url, Hook hook) {}

  /** Yields the next request of a job, or null when the job is finished. */
  @FunctionalInterface
  private interface Job {
    Req next();
  }

  private record Compra(String cnpj, int ano, int sequencial) {}

  private record Trigger(Instant at, Runnable action) {}

  // why: PNCP answered 429 at 0.5 req/s (ADR-0005, ADR-0017); 0.2 req/s is the rate of the app
  static final Duration BASE_INTERVAL = Duration.ofSeconds(5);
  private static final int MAX_UPDATE_PAGES = 40;
  private static final int RAMP_STEPS = 8;
  private static final Duration HOUR = Duration.ofHours(1);
  private static final String API = "/api/consulta/v1";
  private static final String PUBLICACAO = API + "/contratacoes/publicacao";
  private static final String ATUALIZACAO = API + "/contratacoes/atualizacao";
  private static final String PROPOSTA = API + "/contratacoes/proposta";
  private static final String COMPRA = "/orgaos/{cnpj}/compras/{ano}/{sequencial}";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Config config;
  private final Transport transport;
  private final InstantSource clock;
  private final Consumer<ObjectNode> sink;
  private final Executor executor;
  private final RandomGenerator random;
  private final RateGate gate;
  private final Deque<Job> ring = new ArrayDeque<>();
  private final PriorityQueue<Trigger> triggers =
      new PriorityQueue<>((a, b) -> a.at().compareTo(b.at()));
  private final AtomicInteger stop = new AtomicInteger();
  private final AtomicLong requests = new AtomicLong();
  private volatile Compra compra;
  private boolean optionalStarted;
  private int sampleTurn;
  private int samplePages;
  private final Instant start;
  private final Instant end;
  private final LocalDate today;

  public Prober(
      Config config,
      Transport transport,
      InstantSource clock,
      Sleeper sleeper,
      Consumer<ObjectNode> sink,
      Executor executor,
      RandomGenerator random) {
    this.config = config;
    this.transport = transport;
    this.clock = clock;
    this.sink = sink;
    this.executor = executor;
    this.random = random;
    this.gate = new RateGate(clock, sleeper, BASE_INTERVAL);
    this.start = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    this.end = start.plus(config.duration());
    this.today = start.atZone(config.zone()).toLocalDate();
  }

  public long requests() {
    return requests.get();
  }

  /**
   * The run starts when the prober is built. Returns 0 when the duration elapsed, or the HTTP
   * status (429 or 403) that stopped the run.
   */
  public int run() {
    ring.addAll(List.of(this::sample, robots(), deep(1), updates(1, false), volume()));
    at(start.plus(HOUR), () -> ring.add(deep(2)));
    at(start.plus(HOUR), () -> ring.add(updates(2, true)));
    if (config.ramp()) {
      at(rampStart(), this::ramp);
    }
    while (stop.get() == 0 && clock.instant().isBefore(end)) {
      Req req = nextRequest();
      gate.acquire();
      fire(req).join();
      runDueTriggers();
      startOptional();
    }
    return stop.get();
  }

  private Req nextRequest() {
    Req req;
    do {
      Job job = ring.poll();
      req = job.next();
      if (req != null) {
        ring.add(job);
      }
    } while (req == null);
    return req;
  }

  private void at(Instant when, Runnable action) {
    if (when.isBefore(end)) {
      triggers.add(new Trigger(when, action));
    }
  }

  private void runDueTriggers() {
    while (!triggers.isEmpty() && !clock.instant().isBefore(triggers.peek().at())) {
      triggers.poll().action().run();
    }
  }

  private void startOptional() {
    if (!optionalStarted && compra != null) {
      optionalStarted = true;
      ring.add(optional(compra));
    }
  }

  private Instant rampStart() {
    LocalDateTime local = start.atZone(config.zone()).toLocalDateTime();
    LocalDateTime threeAm = local.toLocalDate().atTime(3, 0);
    return (threeAm.isBefore(local) ? threeAm.plusDays(1) : threeAm)
        .atZone(config.zone())
        .toInstant();
  }

  // ---- execution ----

  private CompletableFuture<Void> fire(Req req) {
    return CompletableFuture.runAsync(() -> execute(req), executor);
  }

  private void execute(Req req) {
    Instant at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    Reply reply = transport.send(req.url());
    JsonNode body = parse(reply.body());
    ObjectNode out = JSON.createObjectNode();
    out.put("ts", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.atZone(config.zone())));
    out.put("job", req.job());
    out.put("endpoint", req.endpoint());
    out.put("url", req.url());
    out.put("status", reply.status());
    out.put("latencyMs", reply.latencyMs());
    out.put("bytes", reply.bytes());
    ObjectNode headers = out.putObject("headers");
    reply.headers().forEach((name, values) -> headers.put(name, String.join(", ", values)));
    out.put("error", reply.error());
    summarize(body, out);
    req.hook().accept(reply, body, out);
    requests.incrementAndGet();
    sink.accept(out);
    // robots.txt is only recorded: a refusal there says nothing about the data API rate
    if ((reply.status() == 429 || reply.status() == 403) && !req.job().equals("S9")) {
      stop.compareAndSet(0, reply.status());
    }
  }

  private static JsonNode parse(String text) {
    try {
      return JSON.readTree(text);
    } catch (JacksonException e) {
      return MissingNode.getInstance();
    }
  }

  /** S8 and S10 for every response: sizes are already in the record, add counts and field stats. */
  private void summarize(JsonNode body, ObjectNode out) {
    for (String key : List.of("totalRegistros", "totalPaginas")) {
      if (body.has(key)) {
        out.set(key, body.get(key));
      }
    }
    JsonNode list = Stats.items(body);
    if (list.isArray()) {
      out.put("items", list.size());
      out.set("fieldStats", Stats.fieldStats(list));
      if (compra == null) {
        compra = compraOf(list.path(0));
      }
    }
  }

  private static Compra compraOf(JsonNode item) {
    return item.has("sequencialCompra")
        ? new Compra(
            item.path("orgaoEntidade").path("cnpj").asString(),
            item.path("anoCompra").asInt(),
            item.path("sequencialCompra").asInt())
        : null;
  }

  // ---- urls ----

  private String date(Instant instant) {
    return date(instant.atZone(config.zone()).toLocalDate());
  }

  private static String date(LocalDate day) {
    return DateTimeFormatter.BASIC_ISO_DATE.format(day);
  }

  private String iso(Instant instant) {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(config.zone()));
  }

  private String listUrl(String path, String from, String to, int modalidade, int page, int size) {
    return "%s%s?dataInicial=%s&dataFinal=%s&codigoModalidadeContratacao=%d&pagina=%d&tamanhoPagina=%d"
        .formatted(config.baseUrl(), path, from, to, modalidade, page, size);
  }

  private static Hook noExtras() {
    return (reply, body, out) -> {};
  }

  /** Yesterday as of now, not as of the run start: a 24 h run crosses midnight. */
  private String yesterday() {
    return date(clock.instant().atZone(config.zone()).toLocalDate().minusDays(1));
  }

  // ---- S1, S2, S8, S10: continuous sampler ----

  private Req sample() {
    Instant now = clock.instant();
    String day = date(now);
    return switch (sampleTurn++ % 3) {
      case 0 -> {
        int page = 1 + random.nextInt(Math.max(1, samplePages));
        String yesterday = yesterday();
        yield new Req(
            "S1",
            PUBLICACAO,
            listUrl(PUBLICACAO, yesterday, yesterday, 6, page, 50),
            (reply, body, out) -> samplePages = body.path("totalPaginas").asInt(0));
      }
      case 1 ->
          new Req(
              "S1",
              ATUALIZACAO,
              listUrl(ATUALIZACAO, date(now.minus(HOUR)), day, 6, 1, 50),
              noExtras());
      default ->
          new Req(
              "S1",
              PROPOSTA,
              "%s%s?dataFinal=%s&codigoModalidadeContratacao=6&pagina=1&tamanhoPagina=50"
                  .formatted(
                      config.baseUrl(),
                      PROPOSTA,
                      date(now.atZone(config.zone()).toLocalDate().plusDays(30))),
              noExtras());
    };
  }

  // ---- S9 ----

  private Job robots() {
    Hook text =
        (reply, body, out) ->
            out.put("robotsTxt", reply.body().substring(0, Math.min(2000, reply.body().length())));
    Queue<Req> one = new ArrayDeque<>();
    one.add(new Req("S9", "/robots.txt", config.baseUrl() + "/robots.txt", text));
    return one::poll;
  }

  // ---- S7 ----

  private Job volume() {
    Queue<Req> all = new ArrayDeque<>();
    for (int back = 30; back >= 1; back--) {
      String day = date(today.minusDays(back));
      for (int modalidade = 1; modalidade <= 14; modalidade++) {
        int m = modalidade;
        all.add(
            new Req(
                "S7",
                PUBLICACAO,
                listUrl(PUBLICACAO, day, day, m, 1, 10),
                (reply, body, out) -> out.put("day", day).put("modalidade", m)));
      }
    }
    return all::poll;
  }

  // ---- S4 ----

  private Job deep(int pass) {
    Queue<Req> pages = new ArrayDeque<>();
    // page 1 carries totalPaginas: a failed second pass is retried once
    pages.add(deepRequest(pages, pass, 1, pass == 2));
    return pages::poll;
  }

  private Req deepRequest(Queue<Req> pages, int pass, int page, boolean retriable) {
    String url = listUrl(PUBLICACAO, date(today.minusDays(30)), date(today), 6, page, 50);
    return new Req(
        "S4",
        PUBLICACAO,
        url,
        (reply, body, out) -> {
          out.put("pass", pass).put("page", page);
          if (retriable && reply.status() / 100 != 2) {
            pages.add(deepRequest(pages, pass, page, false));
          }
          ArrayNode ids = out.putArray("ids");
          Stats.items(body).forEach(item -> ids.add(item.path("numeroControlePNCP").asString()));
          if (page == 1) {
            int total = body.path("totalPaginas").asInt(0);
            Stream.of(10, 50, 100, total)
                .filter(p -> p > 1 && p <= total)
                .distinct()
                .sorted()
                .forEach(p -> pages.add(deepRequest(pages, pass, p, false)));
          }
        });
  }

  // ---- S5 ----

  private Job updates(int pass, boolean withNextWindow) {
    Queue<Req> queue = new ArrayDeque<>();
    queue.add(
        updateRequest(
            queue, pass, "W1", start.minus(HOUR.multipliedBy(2)), start.minus(HOUR), 1, pass == 2));
    if (withNextWindow) {
      queue.add(updateRequest(queue, pass, "W2", start.minus(HOUR), start, 1, true));
    }
    return queue::poll;
  }

  private Req updateRequest(
      Queue<Req> queue,
      int pass,
      String window,
      Instant from,
      Instant to,
      int page,
      boolean retriable) {
    return new Req(
        "S5",
        ATUALIZACAO,
        listUrl(ATUALIZACAO, date(from), date(to), 6, page, 50),
        (reply, body, out) -> {
          out.put("pass", pass)
              .put("window", window)
              .put("windowStart", iso(from))
              .put("windowEnd", iso(to))
              .put("page", page);
          if (retriable && reply.status() / 100 != 2) {
            queue.add(updateRequest(queue, pass, window, from, to, page, false));
          }
          ArrayNode entries = out.putArray("entries");
          for (JsonNode item : Stats.items(body)) {
            entries
                .addObject()
                .put("id", item.path("numeroControlePNCP").asString())
                .put("dataAtualizacao", item.path("dataAtualizacao").asString())
                .put("dataAtualizacaoGlobal", item.path("dataAtualizacaoGlobal").asString())
                .put("hash", Hashes.sha256(item.toString()));
          }
          if (page == 1) {
            for (int p = 2;
                p <= Math.min(MAX_UPDATE_PAGES, body.path("totalPaginas").asInt(0));
                p++) {
              queue.add(updateRequest(queue, pass, window, from, to, p, false));
            }
          }
        });
  }

  // ---- S6 ----

  private Job optional(Compra c) {
    String yesterday = yesterday();
    String id = "/orgaos/%s/compras/%d/%d".formatted(c.cnpj(), c.ano(), c.sequencial());
    String window =
        "?dataInicial=%s&dataFinal=%s&pagina=1&tamanhoPagina=10".formatted(yesterday, yesterday);
    Hook cpf = (reply, body, out) -> out.put("cpfLike", Stats.cpfLike(body));
    Queue<Req> all = new ArrayDeque<>();
    all.add(new Req("S6", API + COMPRA, config.baseUrl() + API + id, cpf));
    all.add(new Req("S6", API + "/contratos", config.baseUrl() + API + "/contratos" + window, cpf));
    all.add(new Req("S6", API + "/atas", config.baseUrl() + API + "/atas" + window, cpf));
    all.add(
        new Req(
            "S6",
            "/api/pncp/v1" + COMPRA + "/itens",
            config.baseUrl() + "/api/pncp/v1" + id + "/itens",
            cpf));
    return all::poll;
  }

  // ---- S3 ----

  /** 8 steps from 0.5 to 4 req/s, each sized to last about 7.5 s; in-flight requests overlap. */
  private void ramp() {
    String yesterday = yesterday();
    List<CompletableFuture<Void>> inFlight = new ArrayList<>();
    for (int step = 0; step < RAMP_STEPS && stop.get() == 0; step++) {
      double rate = 0.5 + 0.5 * step;
      int s = step;
      gate.interval(Duration.ofMillis(Math.round(1000 / rate)));
      for (long i = 0, n = Math.round(7.5 * rate); i < n && stop.get() == 0; i++) {
        gate.acquire();
        inFlight.add(
            fire(
                new Req(
                    "S3",
                    PUBLICACAO,
                    listUrl(PUBLICACAO, yesterday, yesterday, 6, 1, 10),
                    (reply, body, out) -> out.put("step", s).put("rate", rate))));
      }
    }
    gate.interval(BASE_INTERVAL);
    CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
  }
}
