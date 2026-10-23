package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import br.dev.miguelsanzbr.radar.ingest.application.port.FeatureFlags;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** The read switch of the slice tests: off, and flipped by the test that wants the cents. */
@TestConfiguration(proxyBeanMethods = false)
class ValueReadSwitchTestConfig {

  static final class Switch implements FeatureFlags {

    private volatile boolean fromCents;

    void fromCents(boolean on) {
      fromCents = on;
    }

    @Override
    public boolean readValueFromCents() {
      return fromCents;
    }
  }

  @Bean
  Switch valueReadSwitch() {
    return new Switch();
  }
}
