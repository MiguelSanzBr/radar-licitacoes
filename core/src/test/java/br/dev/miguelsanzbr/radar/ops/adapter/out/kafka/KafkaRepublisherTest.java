package br.dev.miguelsanzbr.radar.ops.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaRepublisherTest {

  private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
  private final KafkaRepublisher republisher =
      new KafkaRepublisher(template, Duration.ofSeconds(1));

  @Test
  void theMessageGoesToTheTopicWithItsKeyValueAndTheMarkerHeader() {
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

    republisher.republish("radar.procurement.v1", "key", "value");

    ArgumentCaptor<ProducerRecord<String, String>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(template).send(sent.capture());
    assertThat(sent.getValue().topic()).isEqualTo("radar.procurement.v1");
    assertThat(sent.getValue().key()).isEqualTo("key");
    assertThat(sent.getValue().value()).isEqualTo("value");
    assertThat(
            new String(
                sent.getValue().headers().lastHeader("x-reprocessed-from").value(),
                StandardCharsets.UTF_8))
        .isEqualTo("dlq");
  }

  @Test
  void aBrokerFailureSurfacesAsAnException() {
    var failed = new CompletableFuture<SendResult<String, String>>();
    failed.completeExceptionally(new IllegalStateException("broker down"));
    when(template.send(any(ProducerRecord.class))).thenReturn(failed);

    assertThatThrownBy(() -> republisher.republish("radar.procurement.v1", "key", "value"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("radar.procurement.v1");
  }

  @Test
  void aBrokerThatDoesNotAnswerInTimeSurfacesAsAnException() {
    when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
    var impatient = new KafkaRepublisher(template, Duration.ofMillis(50));

    assertThatThrownBy(() -> impatient.republish("radar.procurement.v1", "key", "value"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anInterruptedWaitKeepsTheInterruptAndSurfacesAsAnException() {
    when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
    Thread.currentThread().interrupt();

    try {
      assertThatThrownBy(() -> republisher.republish("radar.procurement.v1", "key", "value"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
