package br.dev.miguelsanzbr.radar.ops.application;

import br.dev.miguelsanzbr.radar.ops.application.port.ConsumerLags;
import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages;
import br.dev.miguelsanzbr.radar.ops.application.port.FlagStates;
import br.dev.miguelsanzbr.radar.ops.application.port.OutboxStats;
import br.dev.miguelsanzbr.radar.ops.application.port.PipelineStats;
import br.dev.miguelsanzbr.radar.ops.domain.Checkpoint;
import br.dev.miguelsanzbr.radar.ops.domain.Component;
import br.dev.miguelsanzbr.radar.ops.domain.ConsumerLag;
import br.dev.miguelsanzbr.radar.ops.domain.Cursor;
import br.dev.miguelsanzbr.radar.ops.domain.DlqCount;
import br.dev.miguelsanzbr.radar.ops.domain.FlagState;
import br.dev.miguelsanzbr.radar.ops.domain.HealthPanel;
import br.dev.miguelsanzbr.radar.ops.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ops.domain.OutboxBacklog;
import br.dev.miguelsanzbr.radar.ops.domain.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Gathers the state of the pipeline in one place. Each section is read on its own: a source that
 * fails turns its component DOWN with the reason and leaves the other sections answering, so the
 * panel is useful exactly when something is broken.
 */
public class HealthPanelUseCase {

  /** Same limit as the OutboxOldestAgeHigh alert (ADR-0025). */
  static final double OUTBOX_OLDEST_AGE_LIMIT_SECONDS = 5.0;

  // shortcut: one fixed limit for every group, turn it into a property when an SLO names one
  static final long CONSUMER_LAG_LIMIT = 1_000;

  private final OutboxStats outbox;
  private final PipelineStats pipeline;
  private final ConsumerLags lags;
  private final DlqMessages dlq;
  private final FlagStates flags;
  private final List<String> dlqTopics;

  public HealthPanelUseCase(
      OutboxStats outbox,
      PipelineStats pipeline,
      ConsumerLags lags,
      DlqMessages dlq,
      FlagStates flags,
      List<String> dlqTopics) {
    this.outbox = outbox;
    this.pipeline = pipeline;
    this.lags = lags;
    this.dlq = dlq;
    this.flags = flags;
    this.dlqTopics = List.copyOf(dlqTopics);
  }

  /** What a section says about itself. */
  private record Verdict(Status status, String detail) {}

  private record Ingestion(List<IngestionRun> runs, List<Cursor> cursors) {}

  public HealthPanel read() {
    var components = new ArrayList<Component>();
    var backlog = section("outbox", components, outbox::read, null, HealthPanelUseCase::outbox);
    var ingestion =
        section(
            "ingestion",
            components,
            () -> new Ingestion(pipeline.lastRuns(), pipeline.cursors()),
            new Ingestion(List.of(), List.of()),
            HealthPanelUseCase::ingestion);
    var consumers =
        section("consumers", components, lags::read, List.of(), HealthPanelUseCase::consumers);
    var dead = section("dlq", components, this::deadLetters, List.of(), HealthPanelUseCase::dlq);
    var checkpoints =
        section(
            "projection",
            components,
            pipeline::checkpoints,
            List.of(),
            HealthPanelUseCase::projection);
    var states = section("flags", components, flags::states, List.of(), HealthPanelUseCase::flags);
    var status =
        components.stream().map(Component::status).max(Comparator.naturalOrder()).orElse(Status.UP);
    return new HealthPanel(
        status,
        components,
        backlog,
        ingestion.runs(),
        ingestion.cursors(),
        consumers,
        dead,
        checkpoints,
        states);
  }

  private List<DlqCount> deadLetters() {
    var waiting = dlq.unreprocessed();
    var counts = new ArrayList<>(waiting);
    dlqTopics.stream()
        .filter(topic -> waiting.stream().noneMatch(count -> count.topic().equals(topic)))
        .forEach(topic -> counts.add(new DlqCount(topic, null, 0)));
    counts.sort(
        Comparator.comparing(DlqCount::topic)
            .thenComparing(
                DlqCount::consumerGroup, Comparator.nullsFirst(Comparator.naturalOrder())));
    return counts;
  }

  private static Verdict outbox(OutboxBacklog backlog) {
    var degraded =
        backlog.oldestAgeSeconds() > OUTBOX_OLDEST_AGE_LIMIT_SECONDS || backlog.poisonPending() > 0;
    return new Verdict(
        degraded ? Status.DEGRADED : Status.UP,
        String.format(
            Locale.ROOT,
            "pendentes %d, mais antigo %.1f s, venenosos %d",
            backlog.pending(),
            backlog.oldestAgeSeconds(),
            backlog.poisonPending()));
  }

  private static Verdict ingestion(Ingestion ingestion) {
    var failed = ingestion.runs().stream().filter(run -> run.status().equals("FAILED")).count();
    return new Verdict(
        failed > 0 ? Status.DEGRADED : Status.UP,
        ingestion.runs().size() + " ultimas execucoes, " + failed + " com falha");
  }

  private static Verdict consumers(List<ConsumerLag> lags) {
    var worst = lags.stream().mapToLong(ConsumerLag::lag).max().orElse(0);
    return new Verdict(
        worst > CONSUMER_LAG_LIMIT ? Status.DEGRADED : Status.UP, "maior atraso " + worst);
  }

  private static Verdict dlq(List<DlqCount> counts) {
    var waiting = counts.stream().mapToLong(DlqCount::unreprocessed).sum();
    return new Verdict(waiting > 0 ? Status.DEGRADED : Status.UP, waiting + " sem reprocessar");
  }

  private static Verdict projection(List<Checkpoint> checkpoints) {
    return new Verdict(
        Status.UP,
        checkpoints.isEmpty()
            ? "nenhum evento projetado ainda"
            : checkpoints.size() + " projetores com ponto de retomada");
  }

  private static Verdict flags(List<FlagState> states) {
    return new Verdict(
        Status.UP,
        states.stream().filter(FlagState::enabled).count() + " de " + states.size() + " ligadas");
  }

  private static <T> T section(
      String name, List<Component> into, Supplier<T> source, T empty, Function<T, Verdict> judge) {
    try {
      var value = source.get();
      var verdict = judge.apply(value);
      into.add(new Component(name, verdict.status(), verdict.detail()));
      return value;
    } catch (RuntimeException e) {
      var reason = e.getMessage() == null ? "" : ": " + e.getMessage();
      into.add(new Component(name, Status.DOWN, e.getClass().getSimpleName() + reason));
      return empty;
    }
  }
}
