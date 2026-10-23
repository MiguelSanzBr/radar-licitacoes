package br.dev.miguelsanzbr.radar.architecture.fixture.adapter.in;

import org.springframework.transaction.annotation.Transactional;

/** Violates the no-transaction-on-inbound-adapter rule at class level (Spring). */
@Transactional
public class TransactionalController {}
