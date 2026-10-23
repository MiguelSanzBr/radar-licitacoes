package br.dev.miguelsanzbr.radar.ops.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.SharedKafka;
import br.dev.miguelsanzbr.radar.ops.domain.ConsumerLag;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaConsumerLagsIT {

  private static final Map<String, Object> ADMIN =
      Map.of("bootstrap.servers", SharedKafka.INSTANCE.getBootstrapServers());

  private static void produce(String topic, int count) throws Exception {
    try (var producer =
        new KafkaProducer<String, String>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedKafka.INSTANCE.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class))) {
      for (var i = 0; i < count; i++) {
        producer.send(new ProducerRecord<>(topic, 0, "k", "v" + i)).get();
      }
    }
  }

  private static void consumeAndCommit(String topic, String group, int count) {
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedKafka.INSTANCE.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
                count,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
      consumer.subscribe(List.of(topic));
      var read = 0;
      var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (read < count && System.nanoTime() < deadline) {
        read += consumer.poll(Duration.ofMillis(300)).count();
      }
      assertThat(read).isEqualTo(count);
      consumer.commitSync();
    }
  }

  @Test
  void theLagIsWhatWasPublishedAndNotYetCommittedPerGroupAndTopic() throws Exception {
    var topic = "radar.lag-it." + UUID.randomUUID();
    var group = "lag-it-" + UUID.randomUUID();
    try (var admin = Admin.create(ADMIN)) {
      admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
    }
    produce(topic, 5);
    consumeAndCommit(topic, group, 3);

    var lags =
        new KafkaConsumerLags(ADMIN, List.of(group, "lag-it-never-seen"), Duration.ofSeconds(30));

    assertThat(lags.read()).containsExactly(new ConsumerLag(group, topic, 2));
  }

  @Test
  void aBrokerThatCannotBeReachedIsAnError() {
    var lags =
        new KafkaConsumerLags(
            Map.of("bootstrap.servers", "127.0.0.1:1"), List.of("g"), Duration.ofMillis(500));

    assertThatThrownBy(lags::read).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anInterruptedReadKeepsTheInterruptAndSurfacesAsAnException() {
    var lags =
        new KafkaConsumerLags(
            Map.of("bootstrap.servers", SharedKafka.INSTANCE.getBootstrapServers()),
            List.of("g"),
            Duration.ofSeconds(5));
    Thread.currentThread().interrupt();

    try {
      assertThatThrownBy(lags::read).isInstanceOf(IllegalStateException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
