package br.dev.miguelsanzbr.radar.ingest.domain;

import static br.dev.miguelsanzbr.radar.ingest.domain.Fixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContentHashTest {

  @Test
  void constructorCopiesTheArray() {
    var bytes = new byte[] {1, 2, 3};
    var hash = new ContentHash(bytes);
    bytes[0] = 9;
    assertThat(hash.value()).containsExactly(1, 2, 3);
  }

  @Test
  void accessorCopiesTheArray() {
    var hash = new ContentHash(new byte[] {1, 2, 3});
    hash.value()[0] = 9;
    assertThat(hash.value()).containsExactly(1, 2, 3);
  }

  @Test
  void rejectsNull() {
    assertThatNullPointerException().isThrownBy(() -> new ContentHash(null));
  }

  @Test
  void equalityIsByContent() {
    var a = new ContentHash(new byte[] {1, 2});
    assertThat(a).isEqualTo(new ContentHash(new byte[] {1, 2}));
    assertThat(a).isNotEqualTo(new ContentHash(new byte[] {1, 3}));
    assertThat(a).isNotEqualTo(new ContentHash(new byte[] {1}));
    assertThat(a).isNotEqualTo(null);
    assertThat(a).isNotEqualTo("0102");
    assertThat(a.hashCode()).isEqualTo(Arrays.hashCode(new byte[] {1, 2}));
  }

  @Test
  void toStringIsLowercaseHex() {
    assertThat(new ContentHash(new byte[] {0x0a, (byte) 0xff, 0x00})).hasToString("0aff00");
  }

  @Test
  void sameContentSameHashIgnoringRawJson() {
    var a = ContentHash.of(snapshot());
    var b = ContentHash.of(snapshot(s -> s.rawJson = "{\"other\":true}"));
    assertThat(a).isEqualTo(b);
    assertThat(a.value()).hasSize(32);
  }

  @Test
  void hashIsSha256OfTheCanonicalForm() {
    var s = snapshot();
    var expected = ContentHash.digest("SHA-256", ContentHash.canonicalForm(s));
    assertThat(ContentHash.of(s)).isEqualTo(expected);
    assertThat(ContentHash.digest("SHA-256", "abc"))
        .hasToString("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void unknownAlgorithmFails() {
    assertThatIllegalStateException().isThrownBy(() -> ContentHash.digest("NOPE-1", "abc"));
  }

  @Test
  void canonicalFormListsEveryFieldInAlphabeticalOrder() {
    assertThat(ContentHash.canonicalForm(snapshot()))
        .isEqualTo(
            "canonical=1\n"
                + "awardedValue=1400.00\n"
                + "disputeMode.id=1\n"
                + "disputeMode.name=Aberto\n"
                + "estimatedValue=1500.50\n"
                + "id=83021808000182-1-000681/2026\n"
                + "legalBasis.code=1\n"
                + "legalBasis.name=Lei 14.133/2021, Art. 28, I\n"
                + "modality.id=6\n"
                + "modality.name=Pregao Eletronico\n"
                + "objectDescription=Aquisicao de material de escritorio\n"
                + "organ.cnpj=83021808000182\n"
                + "organ.name=Prefeitura de Exemplo\n"
                + "pncpUpdatedAt=2026-10-22T10:00:00Z\n"
                + "proposalWindow.closingAt=2026-10-27T12:00:00Z\n"
                + "proposalWindow.openingAt=2026-10-23T12:00:00Z\n"
                + "publishedAt=2026-10-21T12:00:00Z\n"
                + "situation.id=1\n"
                + "situation.name=Divulgada\n"
                + "srp=false\n"
                + "unit.code=1010\n"
                + "unit.ibgeCode=4205407\n"
                + "unit.municipalityName=Exemplo\n"
                + "unit.name=Secretaria de Compras\n"
                + "unit.uf=SC\n");
  }

  @Test
  void canonicalFormWritesNullsAsNul() {
    var s =
        snapshot(
            b -> {
              b.disputeMode = null;
              b.legalBasis = null;
              b.estimatedValue = null;
              b.awardedValue = null;
              b.proposalWindow = null;
              b.unit = new OrganUnit("1", "U", null, null, null);
            });
    var canonical = ContentHash.canonicalForm(s);
    for (var key :
        new String[] {
          "awardedValue",
          "disputeMode.id",
          "disputeMode.name",
          "estimatedValue",
          "legalBasis.code",
          "legalBasis.name",
          "proposalWindow.closingAt",
          "proposalWindow.openingAt",
          "unit.ibgeCode",
          "unit.municipalityName",
          "unit.uf"
        }) {
      assertThat("\n" + canonical).contains("\n" + key + "=\0\n");
    }
  }

  @Test
  void canonicalFormEscapesSeparators() {
    var canonical = ContentHash.canonicalForm(snapshot(b -> b.objectDescription = "a=b\nc\\d\0e"));
    assertThat(canonical).contains("\nobjectDescription=a\\=b\\nc\\\\d\\0e\n");
  }

  @Test
  void escapingKeepsCollidingTextsApart() {
    var withNewline = snapshot(b -> b.objectDescription = "x\nsrp=false");
    var withBackslash = snapshot(b -> b.objectDescription = "x\\nsrp\\=false");
    assertThat(ContentHash.of(withNewline)).isNotEqualTo(ContentHash.of(withBackslash));
    var nul = snapshot(b -> b.estimatedValue = null);
    var text = snapshot(b -> b.objectDescription = "\0");
    assertThat(ContentHash.of(nul)).isNotEqualTo(ContentHash.of(text));
  }

  @Test
  void missingValueDiffersFromZero() {
    var noValue = snapshot(b -> b.awardedValue = null);
    var zero = snapshot(b -> b.awardedValue = Money.ZERO);
    assertThat(ContentHash.of(noValue)).isNotEqualTo(ContentHash.of(zero));
  }

  @Test
  void moneyScaleDoesNotChangeTheHash() {
    var a = snapshot(b -> b.estimatedValue = Money.of("10"));
    var b2 = snapshot(b -> b.estimatedValue = Money.of("10.00"));
    assertThat(ContentHash.of(a)).isEqualTo(ContentHash.of(b2));
  }

  static Stream<Consumer<Fixtures.Builder>> relevantChanges() {
    return Stream.of(
        b ->
            b.id =
                new br.dev.miguelsanzbr.radar.shared.ProcurementId("83021808000182-1-000682/2026"),
        b -> b.situation = new PncpSituation(4, "Suspensa"),
        b -> b.situation = new PncpSituation(1, "Outra"),
        b -> b.organ = new Organ("83021808000183", "Prefeitura de Exemplo"),
        b -> b.organ = new Organ("83021808000182", "Outra"),
        b ->
            b.unit =
                new OrganUnit(
                    "1011", "Secretaria de Compras", Uf.SC, "Exemplo", new IbgeCode("4205407")),
        b -> b.unit = new OrganUnit("1010", "Outra", Uf.SC, "Exemplo", new IbgeCode("4205407")),
        b ->
            b.unit =
                new OrganUnit(
                    "1010", "Secretaria de Compras", Uf.PR, "Exemplo", new IbgeCode("4205407")),
        b ->
            b.unit =
                new OrganUnit(
                    "1010", "Secretaria de Compras", Uf.SC, "Outra", new IbgeCode("4205407")),
        b ->
            b.unit =
                new OrganUnit(
                    "1010", "Secretaria de Compras", Uf.SC, "Exemplo", new IbgeCode("4106902")),
        b -> b.modality = new Modality(7, "Pregao Eletronico"),
        b -> b.modality = new Modality(6, "Outra"),
        b -> b.disputeMode = new DisputeMode(2, "Aberto"),
        b -> b.disputeMode = new DisputeMode(1, "Outro"),
        b -> b.disputeMode = null,
        b -> b.legalBasis = new LegalBasis(2, "Lei 14.133/2021, Art. 28, I"),
        b -> b.legalBasis = new LegalBasis(1, "Outra"),
        b -> b.legalBasis = null,
        b -> b.srp = true,
        b -> b.estimatedValue = Money.of("1500.51"),
        b -> b.estimatedValue = null,
        b -> b.awardedValue = Money.of("1400.01"),
        b -> b.awardedValue = null,
        b ->
            b.proposalWindow =
                new ProposalWindow(
                    b.proposalWindow.openingAt().plusSeconds(1), b.proposalWindow.closingAt()),
        b ->
            b.proposalWindow =
                new ProposalWindow(
                    b.proposalWindow.openingAt(), b.proposalWindow.closingAt().plusSeconds(1)),
        b -> b.proposalWindow = null,
        b -> b.publishedAt = b.publishedAt.plusSeconds(1),
        b -> b.pncpUpdatedAt = b.pncpUpdatedAt.plusSeconds(1),
        b -> b.objectDescription = "Outro objeto",
        b -> b.objectDescription = "");
  }

  @ParameterizedTest
  @MethodSource("relevantChanges")
  void anyRelevantChangeChangesTheHash(Consumer<Fixtures.Builder> change) {
    assertThat(ContentHash.of(snapshot(change))).isNotEqualTo(ContentHash.of(snapshot()));
  }

  @Test
  void instantsAreWrittenInIso() {
    var canonical =
        ContentHash.canonicalForm(
            snapshot(b -> b.publishedAt = Instant.parse("2026-01-02T03:04:05.678Z")));
    assertThat(canonical).contains("\npublishedAt=2026-01-02T03:04:05.678Z\n");
  }
}
