package br.dev.miguelsanzbr.radar.ops.domain;

import java.time.Instant;

public record Cursor(int modalityId, Instant watermark) {}
