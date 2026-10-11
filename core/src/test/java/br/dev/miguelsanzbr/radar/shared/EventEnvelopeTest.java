package br.dev.miguelsanzbr.radar.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.time.InstantSource;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

  record SampleHappened(String aggregateId, Instant occurredAt) implements DomainEvent {}

  private static final Instant AT = Instant.parse("2026-10-22T12:00:00Z");
  private static final UUID ID = new UUID(1, 2);
  private static final SampleHappened EVENT = new SampleHappened("agg-1", AT);

  private static EventEnvelope<SampleHappened> envelope(
      UUID eventId,
      String eventType,
      int schemaVersion,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      Instant occurredAt,
      String traceparent,
      SampleHappened payload) {
    return new EventEnvelope<>(
        eventId,
        eventType,
        schemaVersion,
        aggregateType,
        aggregateId,
        aggregateVersion,
        occurredAt,
        traceparent,
        payload);
  }

  private static EventEnvelope<SampleHappened> valid() {
    return envelope(ID, "SampleHappened", 1, "Sample", "agg-1", 0, AT, null, EVENT);
  }

  @Test
  void acceptsValidFieldsAndNullTraceparent() {
    var envelope = valid();

    assertThat(envelope.traceparent()).isNull();
    assertThat(envelope.payload()).isSameAs(EVENT);
  }

  @Test
  void rejectsNullRequiredFields() {
    assertThatNullPointerException()
        .isThrownBy(() -> envelope(null, "T", 1, "A", "i", 0, AT, null, EVENT));
    assertThatNullPointerException()
        .isThrownBy(() -> envelope(ID, null, 1, "A", "i", 0, AT, null, EVENT));
    assertThatNullPointerException()
        .isThrownBy(() -> envelope(ID, "T", 1, null, "i", 0, AT, null, EVENT));
    assertThatNullPointerException()
        .isThrownBy(() -> envelope(ID, "T", 1, "A", null, 0, AT, null, EVENT));
    assertThatNullPointerException()
        .isThrownBy(() -> envelope(ID, "T", 1, "A", "i", 0, null, null, EVENT));
    assertThatNullPointerException()
        .isThrownBy(() -> envelope(ID, "T", 1, "A", "i", 0, AT, null, null));
  }

  @Test
  void rejectsSchemaVersionBelowOne() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> envelope(ID, "T", 0, "A", "i", 0, AT, null, EVENT));
    assertThat(envelope(ID, "T", 1, "A", "i", 0, AT, null, EVENT).schemaVersion()).isEqualTo(1);
  }

  @Test
  void rejectsNegativeAggregateVersion() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> envelope(ID, "T", 1, "A", "i", -1, AT, null, EVENT));
    assertThat(envelope(ID, "T", 1, "A", "i", 0, AT, null, EVENT).aggregateVersion()).isZero();
  }

  @Test
  void wrapCopiesEventDataAndGeneratesAV7Id() {
    var ids = new UuidV7(InstantSource.fixed(AT), () -> 42L);

    var wrapped = EventEnvelope.wrap(EVENT, "Sample", 3L, 2, "00-trace", ids);

    assertThat(wrapped.eventId().version()).isEqualTo(7);
    assertThat(wrapped.eventType()).isEqualTo("SampleHappened");
    assertThat(wrapped.schemaVersion()).isEqualTo(2);
    assertThat(wrapped.aggregateType()).isEqualTo("Sample");
    assertThat(wrapped.aggregateId()).isEqualTo("agg-1");
    assertThat(wrapped.aggregateVersion()).isEqualTo(3L);
    assertThat(wrapped.occurredAt()).isEqualTo(AT);
    assertThat(wrapped.traceparent()).isEqualTo("00-trace");
    assertThat(wrapped.payload()).isSameAs(EVENT);
  }
}
