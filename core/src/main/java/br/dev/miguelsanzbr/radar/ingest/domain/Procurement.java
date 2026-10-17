package br.dev.miguelsanzbr.radar.ingest.domain;

import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Write-side aggregate of one PNCP notice. PNCP is the source of truth: every snapshot is applied,
 * and transitions outside the state machine are flagged {@code unexpected} instead of rejected.
 *
 * <p>{@code revision} counts event-producing calls (it becomes the envelope aggregate version);
 * {@code version} is the optimistic-lock counter owned by the persistence adapter and never changed
 * here.
 */
public final class Procurement {

  /** Result of {@link #discover}. */
  public record Discovery(Procurement procurement, List<ProcurementEvent> events) {
    public Discovery {
      events = List.copyOf(events);
    }
  }

  private final long version;
  private final Instant firstSeenAt;
  private final List<SituationHistoryEntry> history;

  private PncpSnapshot current;
  private ProcurementStatus status;
  private ContentHash contentHash;
  private Instant lastSeenAt;
  private Instant closedEmittedAt;
  private long revision;

  private Procurement(
      PncpSnapshot current,
      ContentHash contentHash,
      long version,
      List<SituationHistoryEntry> history,
      Instant firstSeenAt,
      Instant lastSeenAt,
      Instant closedEmittedAt,
      long revision) {
    this.current = Objects.requireNonNull(current, "current");
    this.status = ProcurementStatus.fromSituationId(current.situation().id());
    this.version = version;
    this.firstSeenAt = Objects.requireNonNull(firstSeenAt, "firstSeenAt");
    this.history = new ArrayList<>(history);
    this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
    this.lastSeenAt = Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    this.closedEmittedAt = closedEmittedAt;
    this.revision = revision;
  }

  public static Discovery discover(PncpSnapshot s, Clock c) {
    var now = c.instant();
    var first = new SituationHistoryEntry(null, s.situation().id(), false, s.pncpUpdatedAt(), now);
    var procurement = new Procurement(s, ContentHash.of(s), 0, List.of(first), now, now, null, 1);
    var event = new ProcurementDiscovered(s.id().value(), now, procurement.view());
    return new Discovery(procurement, List.of(event));
  }

  /**
   * Rehydration for the persistence adapter. The hash is kept as stored, and the status is derived
   * from the situation id.
   */
  public static Procurement restore(
      PncpSnapshot current,
      ContentHash contentHash,
      long version,
      List<SituationHistoryEntry> history,
      Instant firstSeenAt,
      Instant lastSeenAt,
      Instant closedEmittedAt,
      long revision) {
    return new Procurement(
        current, contentHash, version, history, firstSeenAt, lastSeenAt, closedEmittedAt, revision);
  }

  /**
   * Applies a PNCP snapshot. Snapshots older than the current one are only counted as seen; equal
   * or newer ones are applied.
   */
  public List<ProcurementEvent> applySnapshot(PncpSnapshot incoming, Clock c) {
    if (!id().equals(incoming.id())) {
      throw new IllegalArgumentException("snapshot of " + incoming.id() + " applied to " + id());
    }
    var next = ProcurementStatus.fromSituationId(incoming.situation().id());
    var now = c.instant();
    lastSeenAt = now;
    if (incoming.pncpUpdatedAt().isBefore(current.pncpUpdatedAt())) {
      return List.of();
    }
    // why: publishedAt is the partition key of the stored row, so PNCP cannot move it after
    // discovery
    var s = incoming.withPublishedAt(current.publishedAt());
    var hash = ContentHash.of(s);
    if (hash.equals(contentHash)) {
      return List.of();
    }

    var changedFields = changedFields(s);
    var previousSituation = current.situation();
    var unexpected = !status.allowsTransitionTo(next);
    contentHash = hash;
    current = s;
    status = next;
    revision++;

    var events = new ArrayList<ProcurementEvent>();
    events.add(new ProcurementUpdated(id().value(), now, view(), changedFields));
    if (previousSituation.id() != s.situation().id()) {
      history.add(
          new SituationHistoryEntry(
              previousSituation.id(), s.situation().id(), unexpected, s.pncpUpdatedAt(), now));
      events.add(
          new ProcurementSituationChanged(
              id().value(), now, previousSituation, s.situation(), s.pncpUpdatedAt(), unexpected));
    }
    return List.copyOf(events);
  }

  /** Emits {@link ProcurementClosed} once, when a published notice passes its closing time. */
  public Optional<ProcurementEvent> closeIfExpired(Clock c) {
    var now = c.instant();
    var window = current.proposalWindow();
    if (!(status instanceof ProcurementStatus.Published)
        || closedEmittedAt != null
        || window == null
        || window.closingAt() == null
        || !window.closingAt().isBefore(now)) {
      return Optional.empty();
    }
    closedEmittedAt = now;
    revision++;
    return Optional.of(new ProcurementClosed(id().value(), now, window.closingAt()));
  }

  public ProposalPhase proposalPhase(Instant now) {
    var window = current.proposalWindow();
    return window == null ? ProposalPhase.UNKNOWN : window.phaseAt(now);
  }

  /** Names of the snapshot fields whose value differs from the current state, sorted. */
  private List<String> changedFields(PncpSnapshot s) {
    var before = current.fields();
    var after = s.fields();
    return after.keySet().stream()
        .filter(key -> !Objects.equals(before.get(key), after.get(key)))
        .toList();
  }

  private ProcurementSnapshotView view() {
    return new ProcurementSnapshotView(
        id(),
        status.name(),
        current.situation(),
        current.organ(),
        current.unit(),
        current.modality(),
        current.disputeMode(),
        current.legalBasis(),
        current.srp(),
        current.estimatedValue(),
        current.awardedValue(),
        current.proposalWindow(),
        current.publishedAt(),
        current.pncpUpdatedAt(),
        current.objectDescription(),
        revision);
  }

  public ProcurementId id() {
    return current.id();
  }

  public ContentHash contentHash() {
    return contentHash;
  }

  public long version() {
    return version;
  }

  public long revision() {
    return revision;
  }

  public ProcurementStatus status() {
    return status;
  }

  public PncpSituation situation() {
    return current.situation();
  }

  public Organ organ() {
    return current.organ();
  }

  public OrganUnit unit() {
    return current.unit();
  }

  public Modality modality() {
    return current.modality();
  }

  public DisputeMode disputeMode() {
    return current.disputeMode();
  }

  public LegalBasis legalBasis() {
    return current.legalBasis();
  }

  public boolean srp() {
    return current.srp();
  }

  public Money estimatedValue() {
    return current.estimatedValue();
  }

  public Money awardedValue() {
    return current.awardedValue();
  }

  public ProposalWindow proposalWindow() {
    return current.proposalWindow();
  }

  public Instant publishedAt() {
    return current.publishedAt();
  }

  public Instant pncpUpdatedAt() {
    return current.pncpUpdatedAt();
  }

  public String objectDescription() {
    return current.objectDescription();
  }

  public String rawJson() {
    return current.rawJson();
  }

  public List<SituationHistoryEntry> history() {
    return Collections.unmodifiableList(history);
  }

  public Instant firstSeenAt() {
    return firstSeenAt;
  }

  public Instant lastSeenAt() {
    return lastSeenAt;
  }

  public Instant closedEmittedAt() {
    return closedEmittedAt;
  }
}
