package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import br.dev.miguelsanzbr.radar.identity.application.ApiKeyNotFoundException;
import br.dev.miguelsanzbr.radar.identity.application.ManageApiKeysUseCase;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKey;
import br.dev.miguelsanzbr.radar.identity.domain.ApiKeyLimitExceededException;
import br.dev.miguelsanzbr.radar.identity.domain.Tier;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ApiKeysController.class)
@Import({IdentityProblemAdvice.class, SecurityConfig.class})
class ApiKeysControllerTest {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";

  @Autowired MockMvc mvc;
  @MockitoBean ManageApiKeysUseCase manage;

  @Test
  void createsAKeyForTheCallerAndShowsItOnlyInThatResponse() throws Exception {
    var key = new ApiKey(UUID.randomUUID(), "kc-1", "hash", "ab12cd34", Tier.FREE, Instant.now());
    when(manage.issue("kc-1")).thenReturn(new ApiKey.Issued(key, "rk_ab12cd34_secret"));

    mvc.perform(post("/api/v1/api-keys").with(Auth.supplier("kc-1")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.prefix").value("ab12cd34"))
        .andExpect(jsonPath("$.key").value("rk_ab12cd34_secret"))
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(conforms());
  }

  @Test
  void theLimitOfActiveKeysIsABusinessRuleProblem() throws Exception {
    when(manage.issue("kc-1")).thenThrow(new ApiKeyLimitExceededException(5));

    mvc.perform(post("/api/v1/api-keys").with(Auth.supplier("kc-1")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.detail").value(containsString("5")))
        .andExpect(conforms());
  }

  @Test
  void revokesTheCallersKeyByPrefix() throws Exception {
    mvc.perform(delete("/api/v1/api-keys/ab12cd34").with(Auth.supplier("kc-1")))
        .andExpect(status().isNoContent())
        .andExpect(conforms());

    verify(manage).revoke("kc-1", "ab12cd34");
  }

  @Test
  void aPrefixTheCallerDoesNotOwnIsNotFound() throws Exception {
    doThrow(new ApiKeyNotFoundException()).when(manage).revoke("kc-1", "ab12cd34");

    mvc.perform(delete("/api/v1/api-keys/ab12cd34").with(Auth.supplier("kc-1")))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "not-found.md"))
        .andExpect(conforms());
  }
}
