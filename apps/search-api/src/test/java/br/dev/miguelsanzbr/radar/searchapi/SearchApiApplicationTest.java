package br.dev.miguelsanzbr.radar.searchapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SearchApiApplicationTest {

  @Test
  void mainBootsTheContextUnderTheSearchApiProfile() {
    SearchApiApplication.main(
        new String[] {"--server.port=0", "--spring.profiles.active=aot-train,startup-probe"});

    assertThat(StartupProbe.profiles).contains("search-api");
  }
}
