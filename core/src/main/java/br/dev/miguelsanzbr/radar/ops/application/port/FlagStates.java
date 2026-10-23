package br.dev.miguelsanzbr.radar.ops.application.port;

import br.dev.miguelsanzbr.radar.ops.domain.FlagState;
import java.util.List;

public interface FlagStates {

  /** Every flag with its current state, by name. */
  List<FlagState> states();
}
