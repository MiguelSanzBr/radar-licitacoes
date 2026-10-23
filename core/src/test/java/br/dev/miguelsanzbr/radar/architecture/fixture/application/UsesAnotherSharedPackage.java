package br.dev.miguelsanzbr.radar.architecture.fixture.application;

import br.dev.miguelsanzbr.radar.architecture.fixture.other.shared.ForeignSharedType;

/** Violates the allow-list: only the root's own shared kernel is allowed, not any ..shared.. */
public class UsesAnotherSharedPackage {

  ForeignSharedType foreign;
}
