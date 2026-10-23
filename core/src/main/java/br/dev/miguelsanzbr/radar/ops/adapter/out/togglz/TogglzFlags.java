package br.dev.miguelsanzbr.radar.ops.adapter.out.togglz;

import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.Flags;
import br.dev.miguelsanzbr.radar.ops.application.port.FlagStates;
import br.dev.miguelsanzbr.radar.ops.domain.FlagState;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.togglz.core.Feature;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.util.NamedFeature;

/** The flags as Togglz holds them: features declared by name, state in the JDBC repository. */
@Component
class TogglzFlags implements Flags, FlagStates {

  private final FeatureManager manager;

  TogglzFlags(FeatureManager manager) {
    this.manager = manager;
  }

  @Override
  public boolean isActive(Flag flag) {
    return manager.isActive(new NamedFeature(flag.name()));
  }

  @Override
  public List<FlagState> states() {
    return manager.getFeatures().stream()
        .sorted(Comparator.comparing(Feature::name))
        .map(feature -> new FlagState(feature.name(), manager.isActive(feature)))
        .toList();
  }
}
