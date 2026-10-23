package br.dev.miguelsanzbr.radar.alerts.adapter.out.mail;

import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Queued;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import java.text.NumberFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * The pt-BR e-mails: each one an HTML body (escaped by Thymeleaf, the portal text is not trusted)
 * and a plain-text alternative, from {@code templates/mail}.
 */
@Component
class MailTemplates {

  /** A rendered message. */
  record Mail(String subject, String html, String text) {}

  private static final Locale PT_BR = Locale.of("pt", "BR");
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", PT_BR)
          .withZone(ZoneId.of("America/Sao_Paulo"));
  private static final String UNKNOWN = "não informado";

  private final TemplateEngine engine = new TemplateEngine();
  private final String baseUrl;

  MailTemplates(@Value("${radar.public-base-url:http://localhost:8080}") String baseUrl) {
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    engine.addTemplateResolver(resolver(TemplateMode.HTML, "mail/*.html", 1));
    engine.addTemplateResolver(resolver(TemplateMode.TEXT, "mail/*.txt", 2));
  }

  private static ClassLoaderTemplateResolver resolver(
      TemplateMode mode, String pattern, int order) {
    var resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setTemplateMode(mode);
    resolver.setResolvablePatterns(Set.of(pattern));
    resolver.setCharacterEncoding("UTF-8");
    resolver.setOrder(order);
    return resolver;
  }

  Mail alert(String searchName, AlertMatched alert) {
    var subject = "Nova licitação para a busca \"" + oneLine(searchName) + "\"";
    return render(subject, "alert", Map.of("search", searchName, "item", item(alert)));
  }

  Mail digest(List<Queued> items) {
    var count = items.size();
    var headline =
        "Resumo diário: %d %s".formatted(count, count == 1 ? "nova licitação" : "novas licitações");
    var groups =
        items.stream()
            .collect(
                Collectors.groupingBy(
                    Queued::searchName,
                    LinkedHashMap::new,
                    Collectors.mapping(queued -> item(queued.alert()), Collectors.toList())))
            .entrySet()
            .stream()
            .map(group -> Map.of("name", group.getKey(), "items", group.getValue()))
            .toList();
    return render(headline, "digest", Map.of("headline", headline, "groups", groups));
  }

  Mail verify(String rawToken) {
    return render(
        "Confirme seu e-mail no Radar de Licitações",
        "verify",
        Map.of("link", baseUrl + "/assinatura/confirmar?token=" + rawToken));
  }

  private Mail render(String subject, String template, Map<String, Object> model) {
    var context = new Context(PT_BR);
    context.setVariables(model);
    context.setVariable("searchesUrl", baseUrl + "/buscas/nova");
    context.setVariable("privacyUrl", baseUrl + "/privacidade");
    return new Mail(
        subject,
        engine.process("mail/" + template + ".html", context),
        engine.process("mail/" + template + ".txt", context));
  }

  private Map<String, String> item(AlertMatched alert) {
    var place =
        Stream.of(alert.municipality(), alert.uf())
            .filter(Objects::nonNull)
            .collect(Collectors.joining("/"));
    var value =
        alert.estimatedValue() == null
            ? UNKNOWN
            : NumberFormat.getCurrencyInstance(PT_BR).format(alert.estimatedValue().amount());
    var closing =
        alert.proposalClosingAt() == null ? UNKNOWN : WHEN.format(alert.proposalClosingAt());
    return Map.of(
        "objectDescription",
        alert.objectDescription(),
        "organ",
        alert.organName(),
        "place",
        place.isEmpty() ? UNKNOWN : place,
        "value",
        value,
        "closing",
        closing,
        "url",
        absolute(alert.detailPath()));
  }

  private String absolute(String path) {
    return path.startsWith("/") ? baseUrl + path : baseUrl + "/" + path;
  }

  /** A header must stay on one line whatever the subscriber typed as the search name. */
  private static String oneLine(String text) {
    return text.replaceAll("\\p{Cntrl}", " ");
  }
}
