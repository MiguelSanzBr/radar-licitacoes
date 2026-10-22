package br.dev.miguelsanzbr.radar.ingest.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * One pass over a window of one modality. {@code cursorPage} is the last page fully processed, so a
 * failed or interrupted run resumes at {@link #nextPage()} and the counters keep adding up.
 */
public final class IngestionRun {

  static final int MAX_ERROR = 2000;

  private final Long id;
  private final long version;
  private final IngestionKind kind;
  private final int modalityId;
  private final Instant windowStart;
  private final Instant windowEnd;
  private final Instant startedAt;

  private int cursorPage;
  private RunStatus status;
  private int fetched;
  private int discovered;
  private int updated;
  private int unchanged;
  private Instant finishedAt;
  private String error;

  private IngestionRun(
      Long id,
      long version,
      IngestionKind kind,
      int modalityId,
      Instant windowStart,
      Instant windowEnd,
      int cursorPage,
      RunStatus status,
      int fetched,
      int discovered,
      int updated,
      int unchanged,
      Instant startedAt,
      Instant finishedAt,
      String error) {
    this.id = id;
    this.version = version;
    this.kind = Objects.requireNonNull(kind, "kind");
    this.modalityId = modalityId;
    this.windowStart = Objects.requireNonNull(windowStart, "windowStart");
    this.windowEnd = Objects.requireNonNull(windowEnd, "windowEnd");
    this.cursorPage = cursorPage;
    this.status = Objects.requireNonNull(status, "status");
    this.fetched = fetched;
    this.discovered = discovered;
    this.updated = updated;
    this.unchanged = unchanged;
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    this.finishedAt = finishedAt;
    this.error = error;
  }

  public static IngestionRun start(
      IngestionKind kind, int modalityId, Instant windowStart, Instant windowEnd, Clock clock) {
    return new IngestionRun(
        null,
        0,
        kind,
        modalityId,
        windowStart,
        windowEnd,
        0,
        RunStatus.RUNNING,
        0,
        0,
        0,
        0,
        clock.instant(),
        null,
        null);
  }

  /** Rehydration for the persistence adapter, which owns {@code version} (optimistic lock). */
  public static IngestionRun restore(
      Long id,
      long version,
      IngestionKind kind,
      int modalityId,
      Instant windowStart,
      Instant windowEnd,
      int cursorPage,
      RunStatus status,
      int fetched,
      int discovered,
      int updated,
      int unchanged,
      Instant startedAt,
      Instant finishedAt,
      String error) {
    return new IngestionRun(
        id,
        version,
        kind,
        modalityId,
        windowStart,
        windowEnd,
        cursorPage,
        status,
        fetched,
        discovered,
        updated,
        unchanged,
        startedAt,
        finishedAt,
        error);
  }

  /** Records a fully processed page; pages are processed strictly in order. */
  public void pageProcessed(int page, int fetched, int discovered, int updated, int unchanged) {
    if (page != nextPage()) {
      throw new IllegalArgumentException("page " + page + " processed, expected " + nextPage());
    }
    this.cursorPage = page;
    this.fetched += fetched;
    this.discovered += discovered;
    this.updated += updated;
    this.unchanged += unchanged;
  }

  public void finish(Clock clock) {
    status = RunStatus.DONE;
    finishedAt = clock.instant();
  }

  public void fail(String message, Clock clock) {
    status = RunStatus.FAILED;
    finishedAt = clock.instant();
    error = message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message;
  }

  /** Back to RUNNING after a failure or a crash; cursor and counters stay. */
  public void resume(Clock clock) {
    if (status == RunStatus.DONE) {
      throw new IllegalStateException("run " + id + " is DONE and cannot be resumed");
    }
    status = RunStatus.RUNNING;
    finishedAt = null;
    error = null;
  }

  public int nextPage() {
    return cursorPage + 1;
  }

  public Long id() {
    return id;
  }

  public long version() {
    return version;
  }

  public IngestionKind kind() {
    return kind;
  }

  public int modalityId() {
    return modalityId;
  }

  public Instant windowStart() {
    return windowStart;
  }

  public Instant windowEnd() {
    return windowEnd;
  }

  public int cursorPage() {
    return cursorPage;
  }

  public RunStatus status() {
    return status;
  }

  public int fetched() {
    return fetched;
  }

  public int discovered() {
    return discovered;
  }

  public int updated() {
    return updated;
  }

  public int unchanged() {
    return unchanged;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant finishedAt() {
    return finishedAt;
  }

  public String error() {
    return error;
  }
}
