package br.dev.miguelsanzbr.radar.ingestor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IngestorApplicationTest {

  @Test
  void mainBootsTheContextUnderTheIngestorProfile() {
    IngestorApplication.main(
        new String[] {"--server.port=0", "--spring.profiles.active=aot-train,startup-probe"});

    assertThat(StartupProbe.profiles).contains("ingestor");
  }
}
