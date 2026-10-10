package br.dev.miguelsanzbr.radar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RadarApplicationTest {

  @Test
  void mainStartsTheApplicationContext() {
    RadarApplication.main(
        new String[] {"--spring.main.web-application-type=none", "--spring.profiles.active=noop"});

    assertThat(StartupProbe.STARTED).isTrue();
  }
}
