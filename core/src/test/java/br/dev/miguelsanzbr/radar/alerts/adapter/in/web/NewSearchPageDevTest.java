package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.application.RegisterSubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.VerifySubscriberUseCase;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SubscriptionPages.class)
@Import(ProblemAdvice.class)
@ActiveProfiles("test")
class NewSearchPageDevTest {

  @Autowired MockMvc mvc;
  @MockitoBean RegisterSubscriberUseCase register;
  @MockitoBean VerifySubscriberUseCase verify;

  @Test
  void devAndTestProfilesShowTheSubscriberField() throws Exception {
    mvc.perform(get("/buscas/nova"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("data-dev=\"true\"")));
  }
}
