package br.dev.miguelsanzbr.radar.search.domain;

import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Instant;

/**
 * One hit. {@code snippet} and {@code rank} are null when the query had no text.
 *
 * <p>{@code snippet} is a fragment of text written by the procuring organ with {@code <b>} and
 * {@code </b>} around the matched terms. It is untrusted: a consumer that renders it as HTML must
 * escape everything except those two markers.
 */
public record RankedResult(
    String id,
    String objectDescription,
    String snippet,
    String organName,
    String unitName,
    String uf,
    String municipalityName,
    String ibgeCode,
    int modalityId,
    String status,
    Money estimatedValue,
    Instant proposalOpeningAt,
    Instant proposalClosingAt,
    Instant publishedAt,
    Double rank) {}
