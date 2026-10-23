package br.dev.miguelsanzbr.radar.alerts.adapter.out.flags;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.ops.Flag;
import org.junit.jupiter.api.Test;

class OpsFeatureFlagsTest {

  @Test
  void theLimitIsEnforcedWhileItsFlagIsOn() {
    assertThat(
            new OpsFeatureFlags(flag -> flag == Flag.MAX_SAVED_SEARCHES_LIMIT)
                .savedSearchLimitEnforced())
        .isTrue();
    assertThat(new OpsFeatureFlags(flag -> false).savedSearchLimitEnforced()).isFalse();
  }
}
