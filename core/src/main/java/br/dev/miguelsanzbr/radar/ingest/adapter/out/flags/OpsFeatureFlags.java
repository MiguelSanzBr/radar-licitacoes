package br.dev.miguelsanzbr.radar.ingest.adapter.out.flags;

import br.dev.miguelsanzbr.radar.ingest.application.port.FeatureFlags;
import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.Flags;
import org.springframework.stereotype.Component;

@Component("ingestOpsFeatureFlags")
class OpsFeatureFlags implements FeatureFlags {

  private final Flags flags;

  OpsFeatureFlags(Flags flags) {
    this.flags = flags;
  }

  @Override
  public boolean readValueFromCents() {
    return flags.isActive(Flag.READ_VALUE_CENTS);
  }
}
