package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import br.dev.miguelsanzbr.radar.alerts.application.InvalidTokenException;
import br.dev.miguelsanzbr.radar.alerts.application.RegisterSubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.VerifySubscriberUseCase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.util.HtmlUtils;

/** Browser-facing pages (pt-BR): consent form, confirmation link, new search form, privacy. */
@Controller
class SubscriptionPages {

  private static final MediaType TEXT = MediaType.parseMediaType("text/plain;charset=UTF-8");

  private final RegisterSubscriberUseCase register;
  private final VerifySubscriberUseCase verify;
  private final Resource consentPage;
  private final Resource newSearchPage;

  SubscriptionPages(
      RegisterSubscriberUseCase register,
      VerifySubscriberUseCase verify,
      @Value("classpath:pages/assinatura.html") Resource consentPage,
      @Value("classpath:pages/buscas-nova.html") Resource newSearchPage) {
    this.register = register;
    this.verify = verify;
    this.consentPage = consentPage;
    this.newSearchPage = newSearchPage;
  }

  /** The CSRF token goes into a hidden field so the form also works without scripts. */
  @GetMapping(path = "/assinatura", produces = "text/html;charset=UTF-8")
  @ResponseBody
  String subscribeForm(CsrfToken csrf) throws IOException {
    return consentPage
        .getContentAsString(StandardCharsets.UTF_8)
        .replace(
            "name=\"_csrf\" value=\"\"",
            "name=\"_csrf\" value=\"" + HtmlUtils.htmlEscape(csrf.getToken()) + "\"");
  }

  @GetMapping(path = "/buscas/nova", produces = "text/html;charset=UTF-8")
  @ResponseBody
  String newSearchForm() throws IOException {
    return newSearchPage.getContentAsString(StandardCharsets.UTF_8);
  }

  @GetMapping("/privacidade")
  String privacy() {
    return "forward:/privacidade.html";
  }

  /**
   * The e-mail is the logged-in account's and the provider must have verified it, so nobody
   * subscribes an address they do not own.
   */
  @PostMapping("/assinatura")
  @ResponseBody
  ResponseEntity<String> subscribe(
      @AuthenticationPrincipal OidcUser user,
      @RequestParam(defaultValue = "false") boolean consent) {
    if (!Boolean.TRUE.equals(user.getEmailVerified())) {
      throw new EmailNotVerifiedException();
    }
    register.register(user.getEmail(), consent, user.getSubject());
    return ResponseEntity.accepted()
        .contentType(TEXT)
        .body("Enviamos um link de confirmação para o seu e-mail. Ele vale por 48 horas.");
  }

  @GetMapping("/assinatura/confirmar")
  @ResponseBody
  ResponseEntity<String> confirm(@RequestParam String token) {
    try {
      verify.verify(token);
    } catch (InvalidTokenException e) {
      return ResponseEntity.badRequest()
          .contentType(TEXT)
          .body("Este link é inválido ou expirou. Cadastre-se de novo em /assinatura.");
    }
    return ResponseEntity.ok()
        .contentType(TEXT)
        .body("E-mail confirmado. Você passará a receber os alertas das suas buscas.");
  }
}
