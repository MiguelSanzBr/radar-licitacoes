package br.dev.miguelsanzbr.radar.alerts.adapter.out.flags;

import br.dev.miguelsanzbr.radar.alerts.application.port.FeatureFlags;
import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.Flags;
import org.springframework.stereotype.Component;

@Component
class OpsFeatureFlags implements FeatureFlags {

  private final Flags flags;

  OpsFeatureFlags(Flags flags) {
    this.flags = flags;
  }

  @Override
  public boolean savedSearchLimitEnforced() {
    return flags.isActive(Flag.MAX_SAVED_SEARCHES_LIMIT);
  }
}
