package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.MutableClock;
import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import br.dev.miguelsanzbr.radar.search.application.DetailUseCase;
import br.dev.miguelsanzbr.radar.search.application.ProcurementDetail;
import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import br.dev.miguelsanzbr.radar.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The detail carries the phase, which the clock changes, so the validator must follow it. */
@WebMvcTest(controllers = ProcurementsController.class)
@Import({SearchProblemAdvice.class, SecurityConfig.class, DetailEtagPhaseTest.Moving.class})
class DetailEtagPhaseTest {

  private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
  private static final String PATH = "/api/v1/procurements/12345678000190-1-000123-2026";

  @TestConfiguration(proxyBeanMethods = false)
  static class Moving {
    @Bean
    MutableClock clock() {
      return new MutableClock(NOW);
    }
  }

  @Autowired MockMvc mvc;
  @Autowired MutableClock clock;
  @MockitoBean SearchUseCase search;
  @MockitoBean DetailUseCase detail;

  private ProcurementDetail stored() {
    // relative to the clock, which keeps moving across the tests of this context
    var now = clock.instant();
    return new ProcurementDetail(
        "12345678000190-1-000123/2026",
        "Objeto",
        "12345678000190",
        "Órgão",
        null,
        null,
        null,
        null,
        null,
        6,
        "Pregão",
        null,
        null,
        false,
        "PUBLISHED",
        Money.of("10"),
        null,
        now.minusSeconds(60),
        now.plusSeconds(3600),
        now.minusSeconds(7200),
        now.minusSeconds(60),
        List.of(),
        "same-stored-etag");
  }

  @Test
  void theSameStoredEtagGivesAnotherValidatorOnceTheWindowCloses() throws Exception {
    when(detail.find("12345678000190-1-000123/2026")).thenReturn(stored());

    var open = mvc.perform(get(PATH)).andExpect(jsonPath("$.proposalPhase").value("OPEN"));
    var openTag = open.andReturn().getResponse().getHeader("ETag");
    var repeated = mvc.perform(get(PATH)).andReturn().getResponse().getHeader("ETag");
    clock.advance(Duration.ofHours(2));
    var closed =
        mvc.perform(get(PATH))
            .andExpect(jsonPath("$.proposalPhase").value("CLOSED"))
            .andExpect(conforms())
            .andReturn()
            .getResponse();

    assertThat(repeated).isEqualTo(openTag);
    assertThat(closed.getHeader("ETag")).isNotEqualTo(openTag);
    assertThat(closed.getContentAsString())
        .contains("\"etag\":\"" + closed.getHeader("ETag").replace("\"", "") + "\"");
  }

  @Test
  void aTagFromBeforeTheClosingNoLongerGivesNotModified() throws Exception {
    when(detail.find("12345678000190-1-000123/2026")).thenReturn(stored());
    var openTag = mvc.perform(get(PATH)).andReturn().getResponse().getHeader("ETag");
    mvc.perform(get(PATH).header("If-None-Match", openTag)).andExpect(status().isNotModified());

    clock.advance(Duration.ofHours(2));

    mvc.perform(get(PATH).header("If-None-Match", openTag))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.proposalPhase").value("CLOSED"));
  }
}
