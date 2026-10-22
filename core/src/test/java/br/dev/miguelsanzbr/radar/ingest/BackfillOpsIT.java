package br.dev.miguelsanzbr.radar.ingest;

import static br.dev.miguelsanzbr.radar.ingest.BackfillTestSupport.PNCP;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@BackfillSpringBootTest
class BackfillOpsIT {

  /** 02:00 in Sao Paulo, inside the default 00:00 to 06:00 window. */
  private static final Instant NIGHT = Instant.parse("2026-10-22T05:00:00Z");

  private static final Instant NOON = Instant.parse("2026-10-22T15:00:00Z");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired BackfillTestSupport.MutableClock clock;

  @BeforeEach
  @AfterEach
  void clean() {
    clock.set(NIGHT);
    PNCP.resetAll();
    jdbc.execute("TRUNCATE ingest.ingestion_run");
  }

  private int runs(String where) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM ingest.ingestion_run WHERE " + where, Integer.class);
  }

  @Test
  void insideTheNightWindowItAcceptsAndRunsTheBackfillInTheBackground() throws Exception {
    PNCP.stubFor(get(urlPathEqualTo(BackfillTestSupport.PUBLICATION)).willReturn(noContent()));

    mvc.perform(
            post("/ops/backfill")
                .param("from", "2026-10-20")
                .param("to", "2026-10-21")
                .param("modality", "6"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.from").value("2026-10-20"))
        .andExpect(jsonPath("$.to").value("2026-10-21"))
        .andExpect(jsonPath("$.modality").value(6));

    await()
        .untilAsserted(
            () -> assertThat(runs("status = 'DONE' AND kind = 'BACKFILL'")).isEqualTo(2));
  }

  @Test
  void outsideTheWindowItRefusesWithAProblemUnlessForced() throws Exception {
    clock.set(NOON);
    PNCP.stubFor(get(urlPathEqualTo(BackfillTestSupport.PUBLICATION)).willReturn(noContent()));

    mvc.perform(post("/ops/backfill").param("from", "2026-10-20").param("to", "2026-10-20"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(409));
    assertThat(runs("true")).isZero();

    mvc.perform(
            post("/ops/backfill")
                .param("from", "2026-10-20")
                .param("to", "2026-10-20")
                .param("force", "true"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.modality").value(6));
    await().untilAsserted(() -> assertThat(runs("status = 'DONE'")).isEqualTo(1));
  }

  @Test
  void invalidRangesAreProblemJson400AndStartNothing() throws Exception {
    mvc.perform(post("/ops/backfill").param("from", "2026-10-21").param("to", "2026-10-20"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    mvc.perform(post("/ops/backfill").param("from", "2025-01-01").param("to", "2026-10-20"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    assertThat(runs("true")).isZero();
    assertThat(PNCP.getAllServeEvents()).isEmpty();
  }

  @Test
  void missingOrMalformedParametersAreProblemJson400() throws Exception {
    mvc.perform(post("/ops/backfill").param("from", "2026-10-20"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    mvc.perform(post("/ops/backfill").param("from", "yesterday").param("to", "2026-10-20"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    mvc.perform(
            post("/ops/backfill")
                .param("from", "2026-10-20")
                .param("to", "2026-10-20")
                .param("modality", "six"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    assertThat(runs("true")).isZero();
  }

  @Test
  void aRangeBeingBackfilledRightNowIsAConflict() throws Exception {
    PNCP.stubFor(
        get(urlPathEqualTo(BackfillTestSupport.PUBLICATION))
            .willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                    .withFixedDelay(1500)
                    .withStatus(204)));
    mvc.perform(post("/ops/backfill").param("from", "2026-10-20").param("to", "2026-10-20"))
        .andExpect(status().isAccepted());
    // the run row is saved while the window is held, before the delayed PNCP call answers
    await().until(() -> runs("status = 'RUNNING'") == 1);

    mvc.perform(
            post("/ops/backfill")
                .param("from", "2026-10-20")
                .param("to", "2026-10-20")
                .param("force", "true"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    await().untilAsserted(() -> assertThat(runs("status = 'DONE'")).isEqualTo(1));
  }
}
