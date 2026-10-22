package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.application.EmailAlreadyRegisteredException;
import br.dev.miguelsanzbr.radar.alerts.application.InvalidTokenException;
import br.dev.miguelsanzbr.radar.alerts.application.RegisterSubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.VerifySubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.domain.InvalidInputException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SubscriptionPages.class)
@Import(ProblemAdvice.class)
class SubscriptionPagesTest {

  @Autowired MockMvc mvc;
  @MockitoBean RegisterSubscriberUseCase register;
  @MockitoBean VerifySubscriberUseCase verify;

  @Test
  void staticPagesAreForwarded() throws Exception {
    mvc.perform(get("/assinatura")).andExpect(forwardedUrl("/assinatura.html"));
    mvc.perform(get("/privacidade")).andExpect(forwardedUrl("/privacidade.html"));
  }

  @Test
  void newSearchPageHasAMainLandmarkAndHidesTheDevOnlyField() throws Exception {
    mvc.perform(get("/buscas/nova"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("<main")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("data-dev=\"false\"")));
  }

  @Test
  void missingTokenIs400Problem() throws Exception {
    mvc.perform(get("/assinatura/confirmar"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void formSubmissionWithConsentIsAccepted() throws Exception {
    mvc.perform(post("/assinatura").param("email", "fulano@example.com").param("consent", "on"))
        .andExpect(status().isAccepted())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("link de confirmação")));

    verify(register).register("fulano@example.com", true);
  }

  @Test
  void missingCheckboxMeansNoConsent() throws Exception {
    doThrow(new InvalidInputException("consent is required"))
        .when(register)
        .register("fulano@example.com", false);

    mvc.perform(post("/assinatura").param("email", "fulano@example.com"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value("consent is required"));
  }

  @Test
  void duplicateEmailIs409Problem() throws Exception {
    doThrow(new EmailAlreadyRegisteredException())
        .when(register)
        .register(any(), any(Boolean.class));

    mvc.perform(post("/assinatura").param("email", "fulano@example.com").param("consent", "on"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/conflict.md"));
  }

  @Test
  void confirmationLinkVerifiesTheSubscriber() throws Exception {
    mvc.perform(get("/assinatura/confirmar").param("token", "abc"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("confirmado")));

    verify(verify).verify("abc");
  }

  @Test
  void invalidOrExpiredLinkIs400() throws Exception {
    doThrow(new InvalidTokenException()).when(verify).verify("abc");

    mvc.perform(get("/assinatura/confirmar").param("token", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("inválido ou expirou")));
  }
}
