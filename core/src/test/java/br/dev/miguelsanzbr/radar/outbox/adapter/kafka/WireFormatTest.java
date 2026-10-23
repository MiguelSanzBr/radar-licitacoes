package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microsoft.kiota.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WireFormatTest {

  private static ApiException status(int code) {
    return new ApiException("registry answered " + code) {
      {
        setResponseStatusCode(code);
      }
    };
  }

  @Test
  void anIoFailureAnywhereInTheCauseChainIsTransient() {
    assertThat(WireFormat.transientFailure(new ConnectException("refused"))).isTrue();
    assertThat(
            WireFormat.transientFailure(
                new IllegalStateException(
                    "wrapped", new UncheckedIOException(new IOException("x")))))
        .isTrue();
  }

  @Test
  void aTimeoutOrAnInterruptionIsTransient() {
    assertThat(WireFormat.transientFailure(new RuntimeException(new TimeoutException()))).isTrue();
    assertThat(WireFormat.transientFailure(new RuntimeException(new InterruptedException())))
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {408, 429, 500, 502, 503, 504})
  void aRegistryAnswerThatSaysTryAgainIsTransient(int code) {
    assertThat(WireFormat.transientFailure(new RuntimeException(status(code)))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 400, 404, 409, 422})
  void aRegistryAnswerThatRefusesTheRequestIsPermanent(int code) {
    assertThat(WireFormat.transientFailure(new RuntimeException(status(code)))).isFalse();
  }

  @Test
  void anythingElseIsPermanent() {
    assertThat(WireFormat.transientFailure(new IllegalArgumentException("bad bytes"))).isFalse();
    assertThat(WireFormat.transientFailure(new NullPointerException())).isFalse();
  }

  @Test
  void theClientRetriesAreBoundedByDefault() {
    var config = WireFormat.registryConfig(Map.of());

    assertThat(config)
        .containsEntry("apicurio.registry.retry-count", "2")
        .containsEntry("apicurio.registry.retry-backoff-ms", "200");
  }

  @Test
  void theRegistryTimeoutDefaultsToTenSecondsAndFollowsTheConfig() {
    assertThat(WireFormat.registryTimeout(Map.of())).isEqualTo(Duration.ofSeconds(10));
    assertThat(WireFormat.registryTimeout(Map.of(WireFormat.REGISTRY_TIMEOUT_MS, "250")))
        .isEqualTo(Duration.ofMillis(250));
  }

  @Test
  void aCallThatAnswersInTimeReturnsItsValue() {
    assertThat(WireFormat.within(Duration.ofSeconds(5), () -> "ok")).isEqualTo("ok");
  }

  @Test
  void aCallThatFailsKeepsItsFailureInTheCauseChain() {
    assertThatThrownBy(
            () ->
                WireFormat.within(
                    Duration.ofSeconds(5),
                    () -> {
                      throw new UncheckedIOException(new IOException("down"));
                    }))
        .hasRootCauseInstanceOf(IOException.class);
  }

  @Test
  void aCallThatNeverAnswersEndsAtTheBoundAsATimeout() {
    var start = System.nanoTime();

    assertThatThrownBy(
            () ->
                WireFormat.within(
                    Duration.ofMillis(150),
                    () -> {
                      try {
                        new CountDownLatch(1).await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return "late";
                    }))
        .hasRootCauseInstanceOf(TimeoutException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
  }

  @Test
  void aCallerThatIsInterruptedKeepsTheFlagAndFails() {
    Thread.currentThread().interrupt();

    assertThatThrownBy(
            () ->
                WireFormat.within(
                    Duration.ofSeconds(5),
                    () -> {
                      try {
                        new CountDownLatch(1).await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return "late";
                    }))
        .hasRootCauseInstanceOf(InterruptedException.class);
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void aTruncatedOrGarbledAvroBodyIsPermanentEvenThoughItIsAnIoException() {
    assertThat(WireFormat.transientFailure(new RuntimeException(new java.io.EOFException())))
        .isFalse();
    assertThat(
            WireFormat.transientFailure(
                new UncheckedIOException(new java.io.EOFException("truncated"))))
        .isFalse();
    assertThat(
            WireFormat.transientFailure(
                new RuntimeException(new org.apache.avro.AvroRuntimeException("Malformed data"))))
        .isFalse();
  }

  @Test
  void aPermanentCauseWinsOverANetworkOneInTheSameChain() {
    var network = new java.net.SocketException("late");
    network.initCause(new java.io.EOFException("x"));

    assertThat(WireFormat.transientFailure(network)).isFalse();
  }

  @Test
  void theNetworkShapedFailuresAreTransient() {
    assertThat(WireFormat.transientFailure(new java.net.SocketTimeoutException("read"))).isTrue();
    assertThat(WireFormat.transientFailure(new java.net.SocketException("reset"))).isTrue();
    assertThat(WireFormat.transientFailure(new java.net.UnknownHostException("registry"))).isTrue();
    assertThat(WireFormat.transientFailure(new java.net.http.HttpTimeoutException("slow")))
        .isTrue();
    assertThat(
            WireFormat.transientFailure(
                new IOException("HTTP/1.1 header parser received no bytes")))
        .isTrue();
  }

  @Test
  void aCallThatIsGivenUpOnSeesTheInterrupt() throws Exception {
    var seen = new CountDownLatch(1);

    assertThatThrownBy(
            () ->
                WireFormat.within(
                    Duration.ofMillis(100),
                    () -> {
                      try {
                        new CountDownLatch(1).await();
                      } catch (InterruptedException e) {
                        seen.countDown();
                      }
                      return "late";
                    }))
        .hasRootCauseInstanceOf(TimeoutException.class);
    assertThat(seen.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
  }
}
