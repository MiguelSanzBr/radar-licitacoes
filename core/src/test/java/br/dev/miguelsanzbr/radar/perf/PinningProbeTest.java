package br.dev.miguelsanzbr.radar.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class PinningProbeTest {

  /** Class initialization is one of the cases that still pins a virtual thread on JDK 25. */
  static final class BlocksWhileInitializing {
    static {
      try {
        Thread.sleep(80);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    static void touch() {}
  }

  /** A second one: a class initializes only once, so each test needs its own. */
  static final class BlocksWhileInitializingDeep {
    static {
      try {
        Thread.sleep(80);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    static void touch() {}
  }

  @Test
  void seesAVirtualThreadPinnedByABlockingClassInitializer() throws Exception {
    var pins =
        PinningProbe.pinsDuring(
            () -> {
              var done = new CountDownLatch(1);
              Thread.ofVirtual()
                  .start(
                      () -> {
                        BlocksWhileInitializing.touch();
                        done.countDown();
                      });
              try {
                done.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    assertThat(pins).isNotEmpty();
    assertThat(pins)
        .allSatisfy(p -> assertThat(p.getDuration().toMillis()).isGreaterThanOrEqualTo(20));
    assertThat(pins)
        .anySatisfy(
            p ->
                assertThat(PinningProbe.passesThrough(p, "br.dev.miguelsanzbr.radar.perf"))
                    .isTrue());
    // under -Pjfr the snapshot also holds the deep pin of the other test, whose trace is cut
    assertThat(pins.stream().filter(p -> !p.getStackTrace().isTruncated()))
        .noneMatch(p -> PinningProbe.passesThrough(p, "br.dev.miguelsanzbr.radar.ingest"));
  }

  @Test
  void seesNothingWhenNothingPins() throws Exception {
    assertThat(PinningProbe.pinsDuring(() -> {})).isEmpty();
  }

  private static void recurse(int depth, Runnable bottom) {
    if (depth == 0) {
      bottom.run();
    } else {
      recurse(depth - 1, bottom);
    }
  }

  @Test
  void aTraceTooDeepToKeepIsTreatedAsPassingThroughAnyPackage() throws Exception {
    var pins =
        PinningProbe.pinsDuring(
            () -> {
              var done = new CountDownLatch(1);
              Thread.ofVirtual()
                  .start(
                      () ->
                          recurse(
                              400,
                              () -> {
                                BlocksWhileInitializingDeep.touch();
                                done.countDown();
                              }));
              try {
                done.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });

    assertThat(pins).isNotEmpty();
    assertThat(pins.getFirst().getStackTrace().isTruncated()).isTrue();
    assertThat(PinningProbe.passesThrough(pins.getFirst(), "br.dev.miguelsanzbr.radar.ingest"))
        .isTrue();
  }
}
