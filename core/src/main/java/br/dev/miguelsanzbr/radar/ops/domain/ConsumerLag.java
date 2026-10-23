package br.dev.miguelsanzbr.radar.ops.domain;

/** Messages of a topic a consumer group has not committed yet. */
public record ConsumerLag(String group, String topic, long lag) {}
