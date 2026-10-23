package br.dev.miguelsanzbr.radar.alerts.adapter.out.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import br.dev.miguelsanzbr.radar.ingest.adapter.out.outbox.EventSchemas;
import br.dev.miguelsanzbr.radar.observability.TraceparentExtractor;
import br.dev.miguelsanzbr.radar.observability.TraceparentInjector;
import br.dev.miguelsanzbr.radar.outbox.OutboxMessage;
import br.dev.miguelsanzbr.radar.outbox.OutboxWriter;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaEventPublisher;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaPublisherProperties;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import com.networknt.schema.InputFormat;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pushes a real AlertMatched through the production path (OutboxAlertEvents, then the row to
 * envelope assembly of KafkaEventPublisher) and validates the bytes against the shipped schemas.
 */
class AlertMatchedContractTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> HEADERS = new TypeReference<>() {};
  private static final SearchMatch MATCH =
      new SearchMatch(UUID.randomUUID(), UUID.randomUUID(), Frequency.DAILY);

  private static final class RecordingJdbc extends JdbcTemplate {
    final List<Object[]> rows = new ArrayList<>();

    @Override
    public int update(String sql, Object... args) {
      rows.add(args);
      return 1;
    }
  }

  private static String published(AlertMatched event) throws Exception {
    var jdbc = new RecordingJdbc();
    var writer =
        new OutboxWriter(
            jdbc,
            new UuidV7(InstantSource.fixed(Facts.NOW), new SecureRandom()),
            new TraceparentInjector(Tracer.NOOP, Propagator.NOOP));
    new OutboxAlertEvents(writer).matched(event, Facts.NOW);

    KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.<SendResult<String, String>>completedFuture(null));
    var publisher =
        new KafkaEventPublisher(
            template,
            new KafkaPublisherProperties(
                Map.of("Alert", "radar.alert.v1"), Duration.ofSeconds(1), 1),
            new TraceparentExtractor(Tracer.NOOP, Propagator.NOOP));
    var row = jdbc.rows.getFirst();
    publisher.publish(
        new OutboxMessage(
            1,
            (UUID) row[0],
            (String) row[1],
            (String) row[2],
            (String) row[3],
            (Integer) row[4],
            (String) row[5],
            JSON.readValue((String) row[6], HEADERS),
            Facts.NOW,
            0));
    var captor = ArgumentCaptor.<ProducerRecord<String, String>>captor();
    verify(template).send(captor.capture());
    assertThat(captor.getValue().topic()).isEqualTo("radar.alert.v1");
    assertThat(captor.getValue().key()).isEqualTo(MATCH.subscriberId().toString());
    return captor.getValue().value();
  }

  private static boolean valid(String envelope) {
    return EventSchemas.load("envelope").validate(envelope, InputFormat.JSON).isEmpty()
        && EventSchemas.load("alert-matched.v1").validate(envelope, InputFormat.JSON).isEmpty();
  }

  private static String withPayload(String envelope, java.util.function.Consumer<ObjectNode> edit) {
    var tree = JSON.readTree(envelope);
    edit.accept((ObjectNode) tree.get("payload"));
    return JSON.writeValueAsString(tree);
  }

  @Test
  void aFullyPopulatedAlertMatchesItsSchema() throws Exception {
    var envelope = published(AlertMatched.of(MATCH, Facts.procurement().build()));

    assertThat(valid(envelope)).isTrue();
    var payload = JSON.readTree(envelope).get("payload");
    assertThat(payload.get("frequency").asString()).isEqualTo("DAILY");
    assertThat(payload.get("proposalClosingAt").asString()).isEqualTo("2026-10-26T12:00:00Z");
    assertThat(JSON.readTree(envelope).get("aggregateType").asString()).isEqualTo("Alert");
  }

  @Test
  void aSparseProcurementStillProducesAValidAlert() throws Exception {
    var sparse = Facts.procurement().uf(null).value(null).closingAt(null).build();

    var envelope = published(AlertMatched.of(MATCH, sparse));

    assertThat(valid(envelope)).isTrue();
    assertThat(JSON.readTree(envelope).get("payload").get("estimatedValue").isNull()).isTrue();
  }

  @Test
  void theSchemaRejectsWhatTheEmailCannotShow() throws Exception {
    var envelope = published(AlertMatched.of(MATCH, Facts.procurement().build()));

    assertThat(valid(withPayload(envelope, p -> p.put("objectDescription", "a".repeat(301)))))
        .isFalse();
    assertThat(valid(withPayload(envelope, p -> p.put("frequency", "HOURLY")))).isFalse();
    assertThat(valid(withPayload(envelope, p -> p.remove("detailPath")))).isFalse();
    assertThat(valid(withPayload(envelope, p -> p.put("detailPath", "/outra/rota")))).isFalse();
    assertThat(valid(withPayload(envelope, p -> p.put("procurementId", "x")))).isFalse();
    assertThat(valid(withPayload(envelope, p -> p.put("surprise", true)))).isFalse();
    assertThat(valid(withPayload(envelope, p -> p.put("savedSearchId", "not-a-uuid")))).isFalse();
  }

  @Test
  void anotherEventTypeIsNotAnAlert() throws Exception {
    var envelope = published(AlertMatched.of(MATCH, Facts.procurement().build()));

    assertThat(EventSchemas.load("procurement-closed.v1").validate(envelope, InputFormat.JSON))
        .isNotEmpty();
  }
}
