package br.dev.miguelsanzbr.radar.search.application;

/** An event with the Kafka offset of the record it came from. */
public record OffsetEvent(ProcurementEvent event, long offset) {}
