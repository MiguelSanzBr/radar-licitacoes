package br.dev.miguelsanzbr.radar.search.application;

/** An organ name that resembles the text searched, with its trigram similarity (0 to 1). */
public record OrganMatch(String organName, float similarity) {}
