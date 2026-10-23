package br.dev.miguelsanzbr.radar.ops.adapter.config;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.Flags;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.togglz.core.Feature;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.repository.FeatureState;

/** The flags as the application wires them: declared in the properties, stored in Postgres. */
class FlagsIT extends PostgresIT {

  @Autowired Flags flags;
  @Autowired FeatureManager manager;

  @AfterEach
  void backToTheDefaults() {
    jdbc.sql("TRUNCATE ops.togglz_feature").update();
  }

  private Feature feature(Flag flag) {
    return manager.getFeatures().stream()
        .filter(feature -> feature.name().equals(flag.name()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void everyFlagOfTheCodeIsAFeatureOfTheManagerAndNothingElse() {
    var declared = manager.getFeatures().stream().map(Feature::name).collect(Collectors.toSet());

    assertThat(declared)
        .isEqualTo(Arrays.stream(Flag.values()).map(Flag::name).collect(Collectors.toSet()));
  }

  @Test
  void theOldPropertySwitchesAreTheInitialState() {
    // src/test/resources/config/application.properties turns these off; nothing turns the limit off
    var active = Arrays.stream(Flag.values()).filter(flags::isActive).collect(Collectors.toSet());

    assertThat(active).isEqualTo(Set.of(Flag.MAX_SAVED_SEARCHES_LIMIT));
  }

  @Test
  void aFlippedFlagIsStoredInTheOpsSchemaAndSurvivesAFreshRead() {
    manager.setFeatureState(new FeatureState(feature(Flag.INCREMENTAL_SYNC), true));

    assertThat(flags.isActive(Flag.INCREMENTAL_SYNC)).isTrue();
    assertThat(
            jdbc.sql(
                    "SELECT feature_enabled FROM ops.togglz_feature WHERE feature_name ="
                        + " 'INCREMENTAL_SYNC'")
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }
}
