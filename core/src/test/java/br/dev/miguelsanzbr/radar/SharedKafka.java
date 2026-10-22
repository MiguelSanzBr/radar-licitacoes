package br.dev.miguelsanzbr.radar;

import org.testcontainers.kafka.KafkaContainer;

/** One Kafka broker per JVM, shared by every test class that needs one. */
public final class SharedKafka {

  public static final KafkaContainer INSTANCE =
      new KafkaContainer("apache/kafka:4.1.0").withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");

  static {
    INSTANCE.start();
  }

  private SharedKafka() {}
}
