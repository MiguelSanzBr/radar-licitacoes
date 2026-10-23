package br.dev.miguelsanzbr.radar.ingest.adapter.out.flags;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.ops.Flag;
import org.junit.jupiter.api.Test;

class OpsFeatureFlagsTest {

  @Test
  void theValueIsReadFromTheCentsWhileItsFlagIsOn() {
    assertThat(new OpsFeatureFlags(flag -> flag == Flag.READ_VALUE_CENTS).readValueFromCents())
        .isTrue();
    assertThat(new OpsFeatureFlags(flag -> false).readValueFromCents()).isFalse();
  }
}
