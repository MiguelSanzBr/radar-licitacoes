package br.dev.miguelsanzbr.radar.ingest.domain;

import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import java.time.Instant;

/** Normalised aggregate state (everything but the raw JSON) carried by Discovered and Updated. */
public record ProcurementSnapshotView(
    ProcurementId id,
    String status,
    PncpSituation situation,
    Organ organ,
    OrganUnit unit,
    Modality modality,
    DisputeMode disputeMode,
    LegalBasis legalBasis,
    boolean srp,
    Money estimatedValue,
    Money awardedValue,
    ProposalWindow proposalWindow,
    Instant publishedAt,
    Instant pncpUpdatedAt,
    String objectDescription,
    long revision) {}
