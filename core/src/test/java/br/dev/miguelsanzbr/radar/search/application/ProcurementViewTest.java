package br.dev.miguelsanzbr.radar.search.application;

import static br.dev.miguelsanzbr.radar.search.SearchFixtures.id;
import static br.dev.miguelsanzbr.radar.search.SearchFixtures.view;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProcurementViewTest {

  static Stream<Arguments> bands() {
    return Stream.of(
        Arguments.of(null, 0),
        Arguments.of("0.00", 1),
        Arguments.of("49999.99", 1),
        Arguments.of("50000.00", 2),
        Arguments.of("499999.99", 2),
        Arguments.of("500000.00", 3),
        Arguments.of("4999999.99", 3),
        Arguments.of("5000000.00", 4),
        Arguments.of("123456789.00", 4));
  }

  @ParameterizedTest(name = "{0} is band {1}")
  @MethodSource("bands")
  void valueBandFollowsTheBoundaries(String estimated, int band) {
    assertThat(view(id(1), "object", estimated, 1).valueBand()).isEqualTo((short) band);
  }
}
