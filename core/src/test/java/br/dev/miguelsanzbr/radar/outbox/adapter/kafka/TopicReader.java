package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import br.dev.miguelsanzbr.radar.SharedKafka;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads a topic from the beginning with a throwaway consumer group. */
final class TopicReader {

  private TopicReader() {}

  /** Polls until {@code done} accepts everything read so far, or the timeout passes. */
  static List<ConsumerRecord<String, String>> readUntil(
      String topic, Predicate<List<ConsumerRecord<String, String>>> done, Duration timeout) {
    var records = new ArrayList<ConsumerRecord<String, String>>();
    try (var consumer =
        new KafkaConsumer<String, String>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedKafka.INSTANCE.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                UUID.randomUUID().toString(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
      consumer.subscribe(List.of(topic));
      var deadline = System.nanoTime() + timeout.toNanos();
      while (!done.test(records) && System.nanoTime() < deadline) {
        consumer.poll(Duration.ofMillis(200)).forEach(records::add);
      }
    }
    return records;
  }
}
