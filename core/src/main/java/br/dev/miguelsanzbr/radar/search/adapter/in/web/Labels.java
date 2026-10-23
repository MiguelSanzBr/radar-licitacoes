package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import br.dev.miguelsanzbr.radar.search.domain.ProposalPhase;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** What the pages call things: pt-BR names, brazilian money and the time of Brasília. */
final class Labels {

  /** A modality of the portal's domain table. */
  record Modality(int id, String name) {}

  static final List<Modality> MODALITIES =
      List.of(
          new Modality(1, "Leilão - Eletrônico"),
          new Modality(2, "Diálogo Competitivo"),
          new Modality(3, "Concurso"),
          new Modality(4, "Concorrência - Eletrônica"),
          new Modality(5, "Concorrência - Presencial"),
          new Modality(6, "Pregão - Eletrônico"),
          new Modality(7, "Pregão - Presencial"),
          new Modality(8, "Dispensa de Licitação"),
          new Modality(9, "Inexigibilidade"),
          new Modality(10, "Manifestação de Interesse"),
          new Modality(11, "Pré-qualificação"),
          new Modality(12, "Credenciamento"),
          new Modality(13, "Leilão - Presencial"));

  private static final Locale PT_BR = Locale.of("pt", "BR");
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", PT_BR)
          .withZone(ZoneId.of("America/Sao_Paulo"));
  private static final Map<String, String> STATUSES =
      Map.of(
          "PUBLISHED", "Publicada",
          "SUSPENDED", "Suspensa",
          "REVOKED", "Revogada",
          "ANNULLED", "Anulada");
  private static final Map<ProposalPhase, String> PHASES =
      Map.of(
          ProposalPhase.UPCOMING, "Propostas em breve",
          ProposalPhase.OPEN, "Propostas abertas",
          ProposalPhase.CLOSED, "Propostas encerradas",
          ProposalPhase.UNKNOWN, "Prazo não informado");

  private Labels() {}

  static String modality(int id) {
    return MODALITIES.stream()
        .filter(modality -> modality.id() == id)
        .map(Modality::name)
        .findFirst()
        .orElse("Modalidade " + id);
  }

  static String status(String status) {
    return STATUSES.getOrDefault(status, status);
  }

  static String phase(Instant opening, Instant closing, Instant now) {
    return PHASES.get(ProposalPhase.at(opening, closing, now));
  }

  /** Null stays null: the page decides what to say when there is nothing to show. */
  static String money(Money money) {
    return money == null ? null : NumberFormat.getCurrencyInstance(PT_BR).format(money.amount());
  }

  static String when(Instant instant) {
    return instant == null ? null : WHEN.format(instant);
  }

  static String place(String municipality, String uf) {
    return Stream.of(municipality, uf).filter(Objects::nonNull).collect(Collectors.joining("/"));
  }
}
