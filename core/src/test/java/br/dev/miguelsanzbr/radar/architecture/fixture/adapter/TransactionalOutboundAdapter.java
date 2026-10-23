package br.dev.miguelsanzbr.radar.architecture.fixture.adapter;

import org.springframework.transaction.annotation.Transactional;

/** Outbound adapters may open transactions: the rule only covers adapter.in. */
@Transactional
public class TransactionalOutboundAdapter {}
