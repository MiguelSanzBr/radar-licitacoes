package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import br.dev.miguelsanzbr.radar.alerts.application.InvalidTokenException;
import br.dev.miguelsanzbr.radar.alerts.application.RegisterSubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.VerifySubscriberUseCase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** Browser-facing pages (pt-BR): consent form, confirmation link, new search form, privacy. */
@Controller
class SubscriptionPages {

  private static final MediaType TEXT = MediaType.parseMediaType("text/plain;charset=UTF-8");

  private final RegisterSubscriberUseCase register;
  private final VerifySubscriberUseCase verify;

  private final Environment environment;
  private final Resource newSearchPage;

  SubscriptionPages(
      RegisterSubscriberUseCase register,
      VerifySubscriberUseCase verify,
      Environment environment,
      @Value("classpath:pages/buscas-nova.html") Resource newSearchPage) {
    this.register = register;
    this.verify = verify;
    this.environment = environment;
    this.newSearchPage = newSearchPage;
  }

  @GetMapping("/assinatura")
  String subscribeForm() {
    return "forward:/assinatura.html";
  }

  /** The server tells the page whether the dev-only subscriber field applies. */
  @GetMapping(path = "/buscas/nova", produces = "text/html;charset=UTF-8")
  @ResponseBody
  String newSearchForm() throws IOException {
    var dev = environment.acceptsProfiles(Profiles.of("dev", "test"));
    return newSearchPage
        .getContentAsString(StandardCharsets.UTF_8)
        .replace("data-dev=\"false\"", "data-dev=\"" + dev + "\"");
  }

  @GetMapping("/privacidade")
  String privacy() {
    return "forward:/privacidade.html";
  }

  @PostMapping("/assinatura")
  @ResponseBody
  ResponseEntity<String> subscribe(
      @RequestParam String email, @RequestParam(defaultValue = "false") boolean consent) {
    register.register(email, consent);
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
