package br.dev.miguelsanzbr.radar.alerts.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Queued;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MailTemplatesTest {

  private static final String BASE = "https://radar.example.org";
  private static final MailTemplates TEMPLATES = new MailTemplates(BASE + "/");
  private static final UUID SUBSCRIBER = UUID.randomUUID();

  private static AlertMatched alert(Facts facts) {
    return AlertMatched.of(
        new SearchMatch(UUID.randomUUID(), SUBSCRIBER, Frequency.INSTANT), facts.build());
  }

  private static Queued queued(String search, Facts facts) {
    return new Queued(SUBSCRIBER, "fulano@example.com", search, alert(facts));
  }

  @Test
  void aDetailPathWithoutALeadingSlashIsStillJoinedWithExactlyOne() {
    var base = alert(Facts.procurement());
    var noSlash =
        new AlertMatched(
            base.savedSearchId(),
            base.subscriberId(),
            base.procurementId(),
            base.frequency(),
            base.objectDescription(),
            base.organName(),
            base.uf(),
            base.municipality(),
            base.estimatedValue(),
            base.proposalClosingAt(),
            "licitacoes/83021808000182-1-000001-2026");

    var mail = TEMPLATES.alert("Busca", noSlash);

    assertThat(mail.text()).contains(BASE + "/licitacoes/83021808000182-1-000001-2026");
  }

  @Test
  void theAlertSubjectNamesTheSearchInPortuguese() {
    var mail = TEMPLATES.alert("Notebooks SC", alert(Facts.procurement()));

    assertThat(mail.subject()).isEqualTo("Nova licitação para a busca \"Notebooks SC\"");
  }

  @Test
  void aSearchNameCannotInjectHeaderLines() {
    var mail = TEMPLATES.alert("Notebooks\r\nBcc: x@example.com", alert(Facts.procurement()));

    assertThat(mail.subject()).doesNotContain("\r").doesNotContain("\n");
  }

  @Test
  void bothBodiesShowWhatTheSubscriberNeedsToDecide() {
    var mail = TEMPLATES.alert("Notebooks SC", alert(Facts.procurement()));

    for (var body : List.of(mail.html(), mail.text())) {
      assertThat(body)
          .contains("Aquisicao de notebooks para a secretaria de educacao")
          .contains("Prefeitura de Exemplo")
          .contains("Exemplo/SC")
          .contains("R$ 1.500,50")
          .contains("26/10/2026 09:00")
          .contains(BASE + "/licitacoes/83021808000182-1-000001-2026")
          .contains(BASE + "/buscas/nova")
          .contains(BASE + "/privacidade");
    }
  }

  @Test
  void theClosingTimeIsShownInSaoPauloTimeAroundMidnightUtc() {
    var facts = Facts.procurement().closingAt(Instant.parse("2026-10-27T02:30:00Z"));

    var mail = TEMPLATES.alert("Busca", alert(facts));

    assertThat(mail.text()).contains("26/10/2026 23:30");
  }

  @Test
  void largeValuesUseThePortugueseSeparators() {
    var mail = TEMPLATES.alert("Busca", alert(Facts.procurement().value("1234567.08")));

    assertThat(mail.text()).contains("R$ 1.234.567,08");
  }

  @Test
  void whatTheNoticeDoesNotCarryIsSaidSo() {
    var sparse = Facts.procurement().uf(null).value(null).closingAt(null);

    var mail = TEMPLATES.alert("Busca", alert(sparse));

    assertThat(mail.text())
        .contains("Valor estimado: não informado")
        .contains("Encerramento: não informado");
  }

  @Test
  void aLocationWithOnlyOnePartIsShownWithoutTheSlash() {
    var onlyUf = TEMPLATES.alert("Busca", alert(Facts.procurement().municipality(null)));
    var onlyCity = TEMPLATES.alert("Busca", alert(Facts.procurement().uf(null)));
    var neither = TEMPLATES.alert("Busca", alert(Facts.procurement().municipality(null).uf(null)));

    assertThat(onlyUf.text()).contains("Local: SC");
    assertThat(onlyCity.text()).contains("Local: Exemplo\n");
    assertThat(neither.text()).contains("Local: não informado");
  }

  @Test
  void htmlFromThePortalIsEscapedInTheHtmlBodyAndKeptVerbatimInTheTextBody() {
    var facts = Facts.procurement().description("<script>alert(1)</script> & cia");

    var mail = TEMPLATES.alert("<b>x</b>", alert(facts));

    assertThat(mail.html())
        .doesNotContain("<script>")
        .doesNotContain("<b>x</b>")
        .contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; cia");
    assertThat(mail.text()).contains("<script>alert(1)</script> & cia");
  }

  @Test
  void theDigestGroupsTheAlertsBySearchAndCountsThem() {
    var first = queued("Notebooks", Facts.procurement().id("83021808000182-1-000001/2026"));
    var second = queued("Cadeiras", Facts.procurement().id("83021808000182-1-000002/2026"));
    var third = queued("Notebooks", Facts.procurement().id("83021808000182-1-000003/2026"));

    var mail = TEMPLATES.digest(List.of(first, second, third));

    assertThat(mail.subject()).isEqualTo("Resumo diário: 3 novas licitações");
    for (var body : List.of(mail.html(), mail.text())) {
      assertThat(body)
          .contains(BASE + "/licitacoes/83021808000182-1-000001-2026")
          .contains(BASE + "/licitacoes/83021808000182-1-000002-2026")
          .contains(BASE + "/licitacoes/83021808000182-1-000003-2026")
          .containsOnlyOnce("Notebooks")
          .containsOnlyOnce("Cadeiras")
          .contains(BASE + "/buscas/nova");
    }
  }

  @Test
  void aDigestOfOneSaysNovaLicitacaoInTheSingular() {
    var mail = TEMPLATES.digest(List.of(queued("Notebooks", Facts.procurement())));

    assertThat(mail.subject()).isEqualTo("Resumo diário: 1 nova licitação");
  }

  @Test
  void theVerificationLinkCarriesTheTokenToTheConfirmationPage() {
    var mail = TEMPLATES.verify("abc-DEF_123");

    assertThat(mail.subject()).isEqualTo("Confirme seu e-mail no Radar de Licitações");
    for (var body : List.of(mail.html(), mail.text())) {
      assertThat(body)
          .contains(BASE + "/assinatura/confirmar?token=abc-DEF_123")
          .contains("48 horas");
    }
  }
}
