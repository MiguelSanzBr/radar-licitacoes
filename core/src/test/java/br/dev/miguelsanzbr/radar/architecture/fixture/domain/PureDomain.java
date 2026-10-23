package br.dev.miguelsanzbr.radar.architecture.fixture.domain;

import java.util.List;

/** Complies with the domain purity rule. */
public record PureDomain(List<String> names) {}
