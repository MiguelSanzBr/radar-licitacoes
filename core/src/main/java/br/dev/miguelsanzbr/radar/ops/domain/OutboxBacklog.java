package br.dev.miguelsanzbr.radar.ops.domain;

public record OutboxBacklog(long pending, double oldestAgeSeconds, long poisonPending) {}
