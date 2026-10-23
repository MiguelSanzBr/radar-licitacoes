package br.dev.miguelsanzbr.radar.alerts.adapter;

import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.search;
import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.subscriber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.application.MatchProcurementUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.port.AlertEvents;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

class MatchProcurementUseCaseIT extends PostgresIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MatchProcurementUseCase useCase;
  @Autowired MeterRegistry registry;
  @MockitoSpyBean AlertEvents events;

  private UUID subscriber;
  private UUID savedSearch;

  @BeforeEach
  void aSubscriberWithOneSearch() {
    jdbc.sql("DELETE FROM outbox.outbox_event").update();
    subscriber = subscriber(jdbc);
    savedSearch = search(jdbc, subscriber, "notebooks", "{\"uf\":\"SC\"}");
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  private double matchedTotal() {
    return registry.counter("alerts_matched_total").count();
  }

  @Test
  void aMatchingProcurementMakesOneDeliveryAndOneAlertMatchedKeyedBySubscriber() {
    var before = matchedTotal();

    var alerts = useCase.onDiscovered(UUID.randomUUID(), Facts.procurement().build());

    assertThat(alerts).isEqualTo(1);
    var delivery = jdbc.sql("SELECT * FROM alerts.alert_delivery").query().singleRow();
    assertThat(delivery)
        .containsEntry("saved_search_id", savedSearch)
        .containsEntry("procurement_id", Facts.ID)
        .containsEntry("channel", "email");
    assertThat(delivery.get("sent_at")).isNull();
    var row =
        jdbc.sql(
                "SELECT aggregate_type, aggregate_id, event_type, payload::text AS payload"
                    + " FROM outbox.outbox_event")
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("aggregate_type", "Alert")
        .containsEntry("aggregate_id", subscriber.toString())
        .containsEntry("event_type", "AlertMatched");
    var payload = JSON.readTree((String) row.get("payload"));
    assertThat(payload.get("savedSearchId").asString()).isEqualTo(savedSearch.toString());
    assertThat(payload.get("subscriberId").asString()).isEqualTo(subscriber.toString());
    assertThat(payload.get("procurementId").asString()).isEqualTo(Facts.ID);
    assertThat(payload.get("frequency").asString()).isEqualTo("INSTANT");
    assertThat(payload.get("estimatedValue").asString()).isEqualTo("1500.50");
    assertThat(payload.get("detailPath").asString())
        .isEqualTo("/licitacoes/83021808000182-1-000001-2026");
    assertThat(matchedTotal() - before).isEqualTo(1);
    assertThat(registry.timer("alerts_match_duration").count()).isPositive();
  }

  @Test
  void theSameEventDeliveredThreeTimesLeavesOneRowInEachTable() {
    var eventId = UUID.randomUUID();

    var results =
        List.of(
            useCase.onDiscovered(eventId, Facts.procurement().build()),
            useCase.onDiscovered(eventId, Facts.procurement().build()),
            useCase.onDiscovered(eventId, Facts.procurement().build()));

    assertThat(results).containsExactly(1, 0, 0);
    assertThat(count("alerts.alert_delivery")).isEqualTo(1);
    assertThat(count("alerts.processed_event")).isEqualTo(1);
    assertThat(count("outbox.outbox_event")).isEqualTo(1);
    assertThat(jdbc.sql("SELECT consumer FROM alerts.processed_event").query(String.class).single())
        .isEqualTo("alerts-matcher");
  }

  @Test
  void anUpdateOfTheSameProcurementWithChangedTextAddsNoDeliveryAndNoAlert() {
    useCase.onDiscovered(UUID.randomUUID(), Facts.procurement().build());

    var alerts =
        useCase.onUpdated(
            UUID.randomUUID(),
            Facts.procurement().description("Aquisicao de notebooks e monitores").build(),
            List.of("objectDescription"));

    assertThat(alerts).isZero();
    assertThat(count("alerts.alert_delivery")).isEqualTo(1);
    assertThat(count("outbox.outbox_event")).isEqualTo(1);
    assertThat(count("alerts.processed_event")).isEqualTo(2);
  }

  @Test
  void anUpdateThatChangesOnlyTheSituationRunsNoQueryAtAll() {
    useCase.onUpdated(UUID.randomUUID(), Facts.procurement().build(), List.of("situation"));

    assertThat(count("alerts.processed_event")).isZero();
    assertThat(count("alerts.alert_delivery")).isZero();
  }

  @Test
  void anUpdateThatMakesTheProcurementMatchANewSearchAlertsOnlyForThatSearch() {
    useCase.onDiscovered(
        UUID.randomUUID(), Facts.procurement().description("Material de limpeza").build());
    assertThat(count("alerts.alert_delivery")).isZero();

    var alerts =
        useCase.onUpdated(
            UUID.randomUUID(), Facts.procurement().build(), List.of("objectDescription"));

    assertThat(alerts).isEqualTo(1);
    assertThat(count("alerts.alert_delivery")).isEqualTo(1);
  }

  @Test
  void aFailureAfterTheFirstAlertWasWrittenRollsBackEveryRow() {
    search(jdbc, subscriber(jdbc), "notebooks", "{}");
    doCallRealMethod()
        .doThrow(new IllegalStateException("outbox down"))
        .when(events)
        .matched(any(), any());
    var eventId = UUID.randomUUID();
    var facts = Facts.procurement().build();

    assertThatThrownBy(() -> useCase.onDiscovered(eventId, facts))
        .isInstanceOf(IllegalStateException.class);

    assertThat(count("alerts.alert_delivery")).isZero();
    assertThat(count("alerts.processed_event")).isZero();
    assertThat(count("outbox.outbox_event")).isZero();
  }

  @Test
  void aProcurementThatFailsTheFiltersMatchesNothing() {
    var alerts = useCase.onDiscovered(UUID.randomUUID(), Facts.procurement().uf("SP").build());

    assertThat(alerts).isZero();
    assertThat(count("alerts.processed_event")).isEqualTo(1);
    assertThat(count("outbox.outbox_event")).isZero();
  }
}
