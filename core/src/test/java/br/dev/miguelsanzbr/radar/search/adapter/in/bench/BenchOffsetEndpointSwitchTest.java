package br.dev.miguelsanzbr.radar.search.adapter.in.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The OFFSET endpoint exists only when the bench property turns it on. */
class BenchOffsetEndpointSwitchTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean("searchReadDsl", DSLContext.class, () -> mock(DSLContext.class))
          .withUserConfiguration(BenchOffsetController.class);

  @Test
  void isAbsentByDefault() {
    runner.run(context -> assertThat(context).doesNotHaveBean(BenchOffsetController.class));
  }

  @Test
  void isAbsentWhenSwitchedOff() {
    runner
        .withPropertyValues("radar.bench.offset-endpoint=false")
        .run(context -> assertThat(context).doesNotHaveBean(BenchOffsetController.class));
  }

  @Test
  void isPresentWhenSwitchedOn() {
    runner
        .withPropertyValues("radar.bench.offset-endpoint=true")
        .run(context -> assertThat(context).hasSingleBean(BenchOffsetController.class));
  }
}
