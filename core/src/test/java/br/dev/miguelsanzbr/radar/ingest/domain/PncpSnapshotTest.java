package br.dev.miguelsanzbr.radar.ingest.domain;

import static br.dev.miguelsanzbr.radar.ingest.domain.Fixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import br.dev.miguelsanzbr.radar.shared.Money;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PncpSnapshotTest {

  @Test
  void nullDescriptionBecomesEmpty() {
    assertThat(snapshot(b -> b.objectDescription = null).objectDescription()).isEmpty();
  }

  @Test
  void emptyDescriptionIsAccepted() {
    assertThat(snapshot(b -> b.objectDescription = "").objectDescription()).isEmpty();
  }

  @Test
  void descriptionIsKept() {
    assertThat(snapshot().objectDescription()).isEqualTo("Aquisicao de material de escritorio");
  }

  @Test
  void optionalFieldsMayBeNull() {
    assertThatCode(
            () ->
                snapshot(
                    b -> {
                      b.disputeMode = null;
                      b.legalBasis = null;
                      b.estimatedValue = null;
                      b.awardedValue = null;
                      b.proposalWindow = null;
                    }))
        .doesNotThrowAnyException();
  }

  @Test
  void windowWithoutBoundsIsNormalisedToNone() {
    assertThat(snapshot(b -> b.proposalWindow = new ProposalWindow(null, null)).proposalWindow())
        .isNull();
  }

  @Test
  void partialAndFullWindowsAreKept() {
    var opening = new ProposalWindow(Fixtures.T0, null);
    var closing = new ProposalWindow(null, Fixtures.T0);
    assertThat(snapshot(b -> b.proposalWindow = opening).proposalWindow()).isSameAs(opening);
    assertThat(snapshot(b -> b.proposalWindow = closing).proposalWindow()).isSameAs(closing);
    assertThat(snapshot().proposalWindow()).isNotNull();
  }

  @Test
  void withPublishedAtReplacesOnlyThatField() {
    var s = snapshot();
    var moved = s.withPublishedAt(Fixtures.T0);
    assertThat(moved.publishedAt()).isEqualTo(Fixtures.T0);
    assertThat(moved).isEqualTo(snapshot(b -> b.publishedAt = Fixtures.T0));
  }

  @Test
  void zeroEstimatedValueIsAccepted() {
    assertThatCode(() -> snapshot(b -> b.estimatedValue = Money.ZERO)).doesNotThrowAnyException();
  }

  @Test
  void negativeEstimatedValueIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> snapshot(b -> b.estimatedValue = Money.of(-1)));
  }

  static Stream<Consumer<Fixtures.Builder>> missingRequired() {
    return Stream.of(
        b -> b.id = null,
        b -> b.situation = null,
        b -> b.organ = null,
        b -> b.unit = null,
        b -> b.modality = null,
        b -> b.publishedAt = null,
        b -> b.pncpUpdatedAt = null,
        b -> b.rawJson = null);
  }

  @ParameterizedTest
  @MethodSource("missingRequired")
  void requiredFieldsMustBePresent(Consumer<Fixtures.Builder> change) {
    assertThatNullPointerException().isThrownBy(() -> snapshot(change));
  }

  @Test
  void fieldsExposeEverythingButIdAndRaw() {
    var s = snapshot();
    assertThat(s.fields())
        .containsOnlyKeys(
            "situation",
            "organ",
            "unit",
            "modality",
            "disputeMode",
            "legalBasis",
            "srp",
            "estimatedValue",
            "awardedValue",
            "proposalWindow",
            "publishedAt",
            "pncpUpdatedAt",
            "objectDescription")
        .containsEntry("situation", s.situation())
        .containsEntry("srp", false)
        .containsEntry("objectDescription", s.objectDescription());
  }

  @Test
  void fieldsKeepNullValues() {
    assertThat(snapshot(b -> b.disputeMode = null).fields()).containsEntry("disputeMode", null);
  }
}
