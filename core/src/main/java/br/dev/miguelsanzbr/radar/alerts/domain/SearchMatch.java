package br.dev.miguelsanzbr.radar.alerts.domain;

import java.util.UUID;

/** A saved search that matches a procurement, with whom to tell and how often. */
public record SearchMatch(UUID savedSearchId, UUID subscriberId, Frequency frequency) {}
