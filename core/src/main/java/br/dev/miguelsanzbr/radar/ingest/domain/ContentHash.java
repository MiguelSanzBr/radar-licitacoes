package br.dev.miguelsanzbr.radar.ingest.domain;

import br.dev.miguelsanzbr.radar.shared.ProcurementId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/** SHA-256 over the canonical form of a {@link PncpSnapshot} (every field but the raw JSON). */
public record ContentHash(byte[] value) {

  private static final String NULL = "\0";
  private static final String FORMAT_LINE = "canonical=1\n";

  public ContentHash {
    Objects.requireNonNull(value, "value");
    value = value.clone();
  }

  public static ContentHash of(PncpSnapshot snapshot) {
    return digest("SHA-256", canonicalForm(snapshot));
  }

  static ContentHash digest(String algorithm, String text) {
    try {
      return new ContentHash(
          MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("digest algorithm unavailable: " + algorithm, e);
    }
  }

  /**
   * Deterministic {@code key=value\n} lines, keys sorted, null as NUL; backslash, newline, equals
   * and NUL inside values are backslash-escaped so texts cannot collide.
   */
  static String canonicalForm(PncpSnapshot s) {
    var out = new TreeMap<String, String>();
    put(out, "id", s.id(), ProcurementId::value);
    put(out, "situation.id", s.situation(), PncpSituation::id);
    put(out, "situation.name", s.situation(), PncpSituation::name);
    put(out, "organ.cnpj", s.organ(), Organ::cnpj);
    put(out, "organ.name", s.organ(), Organ::name);
    put(out, "unit.code", s.unit(), OrganUnit::code);
    put(out, "unit.name", s.unit(), OrganUnit::name);
    put(out, "unit.uf", s.unit(), u -> u.uf() == null ? null : u.uf().name());
    put(out, "unit.municipalityName", s.unit(), OrganUnit::municipalityName);
    put(out, "unit.ibgeCode", s.unit(), u -> u.ibgeCode() == null ? null : u.ibgeCode().value());
    put(out, "modality.id", s.modality(), Modality::id);
    put(out, "modality.name", s.modality(), Modality::name);
    put(out, "disputeMode.id", s.disputeMode(), DisputeMode::id);
    put(out, "disputeMode.name", s.disputeMode(), DisputeMode::name);
    put(out, "legalBasis.code", s.legalBasis(), LegalBasis::code);
    put(out, "legalBasis.name", s.legalBasis(), LegalBasis::name);
    put(out, "srp", s, PncpSnapshot::srp);
    put(out, "estimatedValue", s.estimatedValue(), m -> m.amount().toPlainString());
    put(out, "awardedValue", s.awardedValue(), m -> m.amount().toPlainString());
    put(out, "proposalWindow.openingAt", s.proposalWindow(), ProposalWindow::openingAt);
    put(out, "proposalWindow.closingAt", s.proposalWindow(), ProposalWindow::closingAt);
    put(out, "publishedAt", s, PncpSnapshot::publishedAt);
    put(out, "pncpUpdatedAt", s, PncpSnapshot::pncpUpdatedAt);
    put(out, "objectDescription", s, PncpSnapshot::objectDescription);

    var text = new StringBuilder(FORMAT_LINE);
    for (Map.Entry<String, String> e : out.entrySet()) {
      text.append(e.getKey()).append('=').append(e.getValue()).append('\n');
    }
    return text.toString();
  }

  private static <T> void put(Map<String, String> out, String key, T owner, Function<T, ?> field) {
    var value = owner == null ? null : field.apply(owner);
    out.put(key, value == null ? NULL : escape(value.toString()));
  }

  private static String escape(String text) {
    var out = new StringBuilder(text.length());
    for (var ch : text.toCharArray()) {
      switch (ch) {
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '=' -> out.append("\\=");
        case '\0' -> out.append("\\0");
        default -> out.append(ch);
      }
    }
    return out.toString();
  }

  @Override
  public byte[] value() {
    return value.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ContentHash that && Arrays.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }

  @Override
  public String toString() {
    return HexFormat.of().formatHex(value);
  }
}
