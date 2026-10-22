package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.alerts.application.EmailAlreadyRegisteredException;
import br.dev.miguelsanzbr.radar.alerts.application.InvalidTokenException;
import br.dev.miguelsanzbr.radar.alerts.application.RegisterSubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.VerifySubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.domain.InvalidInputException;
import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import jakarta.servlet.http.Cookie;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(SubscriptionPages.class)
@Import({ProblemAdvice.class, SecurityConfig.class})
class SubscriptionPagesTest {

  private static final String LOGIN = "/oauth2/authorization/keycloak";

  @Autowired MockMvc mvc;
  @MockitoBean RegisterSubscriberUseCase register;
  @MockitoBean VerifySubscriberUseCase verify;

  @Test
  void privacyPageIsPublic() throws Exception {
    mvc.perform(get("/privacidade")).andExpect(forwardedUrl("/privacidade.html"));
  }

  @Test
  void consentPageAsksVisitorsToLogIn() throws Exception {
    mvc.perform(get("/assinatura")).andExpect(redirectedUrl(LOGIN));
    mvc.perform(get("/buscas/nova")).andExpect(redirectedUrl(LOGIN));
  }

  @Test
  void consentPageCarriesTheCsrfTokenForBrowsersWithoutScripts() throws Exception {
    mvc.perform(get("/assinatura").with(Auth.webSupplier("sub-1", "fulano@example.com")))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        .andExpect(
            content()
                .string(
                    Matchers.matchesPattern(
                        "(?s).*<input type=\"hidden\" name=\"_csrf\" value=\"[^\"]+\">.*")));
  }

  /**
   * Without scripts the form posts the hidden field. The token is stored in the XSRF-TOKEN cookie;
   * once another test used csrf(), the shared context keeps it in the session instead, so the test
   * carries both.
   */
  @Test
  void hiddenCsrfFieldIsAcceptedWhenTheFormIsPostedWithoutScripts() throws Exception {
    var supplier = Auth.webSupplier("sub-1", "fulano@example.com");
    var session = new MockHttpSession();
    var page =
        mvc.perform(get("/assinatura").with(supplier).session(session)).andReturn().getResponse();
    var field =
        Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page.getContentAsString());
    assertThat(field.find()).isTrue();
    var setCookie = page.getHeader("Set-Cookie");

    mvc.perform(formPost(supplier, session, setCookie, field.group(1)))
        .andExpect(status().isAccepted());
    mvc.perform(formPost(supplier, session, setCookie, "forged")).andExpect(status().isForbidden());
  }

  private static MockHttpServletRequestBuilder formPost(
      RequestPostProcessor supplier, MockHttpSession session, String setCookie, String token) {
    var request =
        post("/assinatura")
            .with(supplier)
            .session(session)
            .param("_csrf", token)
            .param("consent", "on");
    return setCookie == null
        ? request
        : request.cookie(
            new Cookie("XSRF-TOKEN", setCookie.replaceFirst("^XSRF-TOKEN=([^;]*).*$", "$1")));
  }

  @Test
  void accountWithUnverifiedEmailCannotConsent() throws Exception {
    mvc.perform(
            post("/assinatura")
                .with(
                    oidcLogin()
                        .idToken(t -> t.subject("sub-1").claim("email", "fulano@example.com"))
                        .authorities(new SimpleGrantedAuthority("ROLE_supplier")))
                .with(csrf())
                .param("consent", "on"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/email-not-verified.md"));

    verifyNoInteractions(register);
  }

  @Test
  void operatorIsNotASupplier() throws Exception {
    mvc.perform(get("/assinatura").with(Auth.webOperator())).andExpect(status().isForbidden());
  }

  @Test
  void newSearchPageHasAMainLandmarkAndNoDebugField() throws Exception {
    mvc.perform(get("/buscas/nova").with(Auth.webSupplier("sub-1", "fulano@example.com")))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"))
        .andExpect(content().string(Matchers.containsString("<main")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("X-Debug"))));
  }

  @Test
  void missingTokenIs400Problem() throws Exception {
    mvc.perform(get("/assinatura/confirmar"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void consentUsesTheAccountEmailAndLinksTheSubject() throws Exception {
    mvc.perform(
            post("/assinatura")
                .with(Auth.webSupplier("sub-1", "fulano@example.com"))
                .with(csrf())
                .param("email", "someone-else@example.com")
                .param("consent", "on"))
        .andExpect(status().isAccepted())
        .andExpect(content().string(Matchers.containsString("link de confirmação")));

    verify(register).register("fulano@example.com", true, "sub-1");
  }

  @Test
  void consentWithoutCsrfTokenIs403AndRegistersNothing() throws Exception {
    mvc.perform(
            post("/assinatura")
                .with(Auth.webSupplier("sub-1", "fulano@example.com"))
                .param("consent", "on"))
        .andExpect(status().isForbidden());

    verifyNoInteractions(register);
  }

  @Test
  void visitorCannotConsent() throws Exception {
    mvc.perform(post("/assinatura").with(csrf()).param("consent", "on"))
        .andExpect(redirectedUrl(LOGIN));

    verifyNoInteractions(register);
  }

  @Test
  void missingCheckboxMeansNoConsent() throws Exception {
    doThrow(new InvalidInputException("consent is required"))
        .when(register)
        .register("fulano@example.com", false, "sub-1");

    mvc.perform(
            post("/assinatura").with(Auth.webSupplier("sub-1", "fulano@example.com")).with(csrf()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value("consent is required"));
  }

  @Test
  void duplicateEmailIs409Problem() throws Exception {
    doThrow(new EmailAlreadyRegisteredException())
        .when(register)
        .register(any(), any(Boolean.class), any());

    mvc.perform(
            post("/assinatura")
                .with(Auth.webSupplier("sub-1", "fulano@example.com"))
                .with(csrf())
                .param("consent", "on"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/conflict.md"));
  }

  @Test
  void confirmationLinkIsPublicAndVerifiesTheSubscriber() throws Exception {
    mvc.perform(get("/assinatura/confirmar").param("token", "abc"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("confirmado")));

    verify(verify).verify("abc");
  }

  @Test
  void invalidOrExpiredLinkIs400() throws Exception {
    doThrow(new InvalidTokenException()).when(verify).verify("abc");

    mvc.perform(get("/assinatura/confirmar").param("token", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(Matchers.containsString("inválido ou expirou")));
  }
}
