package br.dev.miguelsanzbr.radar.ingest;

import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.PNCP;
import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.UPDATE;
import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.id;
import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.itemJson;
import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.stubUpdates;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.ingest.application.CloseExpiredUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncFailedException;
import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@BackfillSpringBootTest
class IncrementalSyncIT {

  /** 12:00 in Sao Paulo. */
  private static final Instant T1 = Instant.parse("2026-10-22T15:00:00Z");

  private static final Instant T2 = T1.plusSeconds(10 * 60);

  @Autowired IncrementalSyncUseCase sync;
  @Autowired CloseExpiredUseCase closeExpired;
  @Autowired JdbcTemplate jdbc;
  @Autowired MeterRegistry meters;
  @Autowired BackfillTestSupport.MutableClock clock;

  @BeforeEach
  @AfterEach
  void clean() {
    clock.set(T1);
    PNCP.resetAll();
    jdbc.execute(
        "TRUNCATE ingest.procurement, ingest.procurement_situation_history,"
            + " ingest.ingestion_run, ingest.ingestion_cursor, outbox.outbox_event");
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private int events(String type) {
    return count("SELECT count(*) FROM outbox.outbox_event WHERE event_type = ?", type);
  }

  private int events(String type, int n) {
    return count(
        "SELECT count(*) FROM outbox.outbox_event WHERE event_type = ? AND aggregate_id = ?",
        type,
        id(n));
  }

  private Instant watermark() {
    return jdbc.queryForObject(
            "SELECT watermark FROM ingest.ingestion_cursor WHERE modality_id = 6", Timestamp.class)
        .toInstant();
  }

  private double items(String outcome) {
    return meters.counter("ingest_items_total", "kind", "incremental", "outcome", outcome).count();
  }

  /** The first sync: window 11:45 of the 21st to 12:00 of the 22nd (Sao Paulo). */
  private void firstSync() {
    stubUpdates(
        "20261021",
        "20261022",
        itemJson(1, 1, "2026-10-22T11:40:00", null),
        itemJson(2, 1, "2026-10-22T11:50:00", null));
    sync.sync(6);
    clock.set(T2);
  }

  @Test
  void overlappingWindowsThatShareItemsEmitNoDuplicateEvent() {
    firstSync();
    assertThat(watermark()).isEqualTo(Instant.parse("2026-10-22T14:50:00Z"));
    // the next window starts 15 min before the watermark: item 2 comes again, item 3 is new
    stubUpdates(
        "20261022",
        "20261022",
        itemJson(2, 1, "2026-10-22T11:50:00", null),
        itemJson(3, 1, "2026-10-22T12:05:00", null));

    var run = sync.sync(6);

    assertThat(run.discovered()).isEqualTo(1);
    assertThat(run.unchanged()).isEqualTo(1);
    assertThat(events("ProcurementDiscovered")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM outbox.outbox_event")).isEqualTo(3);
    assertThat(count("SELECT count(DISTINCT aggregate_id) FROM outbox.outbox_event")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM ingest.procurement")).isEqualTo(3);
    assertThat(watermark()).isEqualTo(Instant.parse("2026-10-22T15:05:00Z"));
    assertThat(count("SELECT count(*) FROM ingest.ingestion_run WHERE kind = 'INCREMENTAL'"))
        .isEqualTo(2);
  }

  @Test
  void anItemChangedBetweenSyncsEmitsOneUpdateAndOneSituationChangeEvenWhenSeenAgain() {
    firstSync();
    stubUpdates(
        "20261022",
        "20261022",
        itemJson(1, 2, "2026-10-22T12:05:00", null),
        itemJson(2, 1, "2026-10-22T11:50:00", null));

    sync.sync(6);
    clock.set(T2.plusSeconds(60));
    sync.sync(6);

    assertThat(events("ProcurementUpdated", 1)).isEqualTo(1);
    assertThat(events("ProcurementSituationChanged", 1)).isEqualTo(1);
    assertThat(events("ProcurementUpdated", 2)).isZero();
    assertThat(events("ProcurementSituationChanged", 2)).isZero();
    assertThat(events("ProcurementDiscovered")).isEqualTo(2);
  }

  @Test
  void closeExpiredEmitsProcurementClosedOnceEvenRunThreeTimes() {
    stubUpdates(
        "20261021", "20261022", itemJson(4, 1, "2026-10-22T11:40:00", "2026-10-22T13:00:00"));
    sync.sync(6);
    assertThat(closeExpired.closeExpired()).isZero();

    clock.set(Instant.parse("2026-10-22T16:30:00Z"));
    var closed =
        List.of(
            closeExpired.closeExpired(), closeExpired.closeExpired(), closeExpired.closeExpired());

    assertThat(closed).containsExactly(1, 0, 0);
    assertThat(events("ProcurementClosed", 4)).isEqualTo(1);
    assertThat(events("ProcurementClosed")).isEqualTo(1);
  }

  @Test
  void itemCountersFollowTheOutcomesOfEachSync() {
    var discovered = items("discovered");
    var updated = items("updated");
    var unchanged = items("unchanged");
    firstSync();
    stubUpdates(
        "20261022",
        "20261022",
        itemJson(1, 2, "2026-10-22T12:05:00", null),
        itemJson(2, 1, "2026-10-22T11:50:00", null),
        itemJson(3, 1, "2026-10-22T12:06:00", null));

    sync.sync(6);

    assertThat(items("discovered") - discovered).isEqualTo(3);
    assertThat(items("updated") - updated).isEqualTo(1);
    assertThat(items("unchanged") - unchanged).isEqualTo(1);
  }

  @Test
  void aFailedSyncLeavesNoCursorAndAFailedRun() {
    PNCP.stubFor(
        get(urlPathEqualTo(UPDATE)).willReturn(aResponse().withStatus(500).withBody("down")));

    assertThatThrownBy(() -> sync.sync(6))
        .isInstanceOf(IncrementalSyncFailedException.class)
        .hasCauseInstanceOf(PncpUnavailableException.class);

    assertThat(count("SELECT count(*) FROM ingest.ingestion_cursor")).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM ingest.ingestion_run"
                    + " WHERE kind = 'INCREMENTAL' AND status = 'FAILED'"))
        .isEqualTo(1);
  }
}
