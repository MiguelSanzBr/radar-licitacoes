package br.dev.miguelsanzbr.radar.ingest.domain;

import br.dev.miguelsanzbr.radar.shared.DomainEvent;

public sealed interface ProcurementEvent extends DomainEvent
    permits ProcurementDiscovered,
        ProcurementUpdated,
        ProcurementSituationChanged,
        ProcurementClosed {}
