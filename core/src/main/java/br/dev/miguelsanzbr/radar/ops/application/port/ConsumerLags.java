package br.dev.miguelsanzbr.radar.ops.application.port;

import br.dev.miguelsanzbr.radar.ops.domain.ConsumerLag;
import java.util.List;

public interface ConsumerLags {

  /** The lag of each watched consumer group per topic; a group that never committed is absent. */
  List<ConsumerLag> read();
}
