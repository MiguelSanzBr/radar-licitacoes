package br.dev.miguelsanzbr.radar.search.application;

import java.math.BigDecimal;

/**
 * One group of the stats: {@code key} is the uf ('XX' when unknown), the modality id, the month as
 * {@code yyyy-MM} or the value band (0 to 4), as text.
 */
public record StatsRow(String key, long count, BigDecimal estimatedSum) {}
