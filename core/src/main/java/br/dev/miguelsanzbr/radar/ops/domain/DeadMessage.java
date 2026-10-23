package br.dev.miguelsanzbr.radar.ops.domain;

import java.time.Instant;
import java.util.Map;

/**
 * A record of a dead-letter topic: its own coordinates, key and value, all its headers as text, the
 * cause the consumer wrote in them and the original record with the group that failed it.
 */
public record DeadMessage(
    String topic,
    int partition,
    long offset,
    String key,
    String value,
    Map<String, String> headers,
    String errorClass,
    String errorMessage,
    String originalTopic,
    int originalPartition,
    long originalOffset,
    String consumerGroup,
    Instant recordedAt) {

  public DeadMessage {
    headers = Map.copyOf(headers);
  }
}
