package br.dev.miguelsanzbr.radar.ops.domain;

/** Messages of a dead-letter topic not reprocessed yet; the group is null for an empty topic. */
public record DlqCount(String topic, String consumerGroup, long unreprocessed) {}
