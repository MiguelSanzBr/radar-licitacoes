package br.dev.miguelsanzbr.radar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RadarApplicationTest {

  @Test
  void mainStartsTheApplicationContextAgainstPostgres() {
    var postgres = SharedPostgres.INSTANCE;

    RadarApplication.main(
        new String[] {
          "--spring.main.web-application-type=none",
          "--spring.profiles.active=noop",
          "--radar.outbox.relay-enabled=false",
          "--radar.outbox.publisher=noop",
          "--spring.datasource.url=" + postgres.getJdbcUrl(),
          "--spring.datasource.username=" + postgres.getUsername(),
          "--spring.datasource.password=" + postgres.getPassword()
        });

    assertThat(StartupProbe.STARTED).isTrue();
  }
}
