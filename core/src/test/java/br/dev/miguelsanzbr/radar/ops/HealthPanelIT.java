package br.dev.miguelsanzbr.radar.ops;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.outbox.adapter.kafka.KafkaSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The panel on the real application: JSON against the contract, the page, and the role matrix. */
@KafkaSpringBootTest
@AutoConfigureMockMvc
class HealthPanelIT {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute("TRUNCATE ops.dlq_message, ingest.ingestion_run, ingest.ingestion_cursor");
  }

  @Test
  void onlyTheOperatorReadsThePanel() throws Exception {
    mvc.perform(get("/ops/health-panel")).andExpect(status().isUnauthorized());
    mvc.perform(get("/ops/health-panel").with(Auth.supplier("s")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/ops/health-panel").with(Auth.operator())).andExpect(status().isOk());
  }

  @Test
  void theJsonConformsToTheContractAndCoversEverySection() throws Exception {
    jdbc.update(
        "INSERT INTO ops.dlq_message (topic, kafka_partition, kafka_offset, headers, recorded_at,"
            + " original_topic, original_partition, original_offset, consumer_group)"
            + " VALUES ('radar.procurement.v1.dlq', 0, 1, '{}', now(), 'radar.procurement.v1', 0, 1,"
            + " 'search-projector')");
    jdbc.update(
        "INSERT INTO ingest.ingestion_cursor (modality_id, watermark, updated_at) VALUES (6, now(),"
            + " now())");

    mvc.perform(get("/ops/health-panel").with(Auth.operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DEGRADED"))
        .andExpect(
            jsonPath("$.components[*].name")
                .value(
                    org.hamcrest.Matchers.contains(
                        "outbox", "ingestion", "consumers", "dlq", "projection", "flags")))
        .andExpect(jsonPath("$.outbox.pending").isNumber())
        .andExpect(jsonPath("$.cursors[0].modalityId").value(6))
        .andExpect(jsonPath("$.dlq[?(@.topic=='radar.procurement.v1.dlq')].unreprocessed").value(1))
        .andExpect(jsonPath("$.dlq.length()").value(2))
        .andExpect(jsonPath("$.flags.length()").value(5))
        .andExpect(jsonPath("$.flags[?(@.name=='MAX_SAVED_SEARCHES_LIMIT')].enabled").value(true))
        .andExpect(conforms());
  }

  @Test
  void thePageIsRenderedForTheOperatorOnly() throws Exception {
    mvc.perform(get("/ops/painel"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/oauth2/authorization/keycloak"));
    mvc.perform(get("/ops/painel").with(Auth.webSupplier("s", "s@example.com")))
        .andExpect(status().isForbidden());

    var page =
        mvc.perform(get("/ops/painel").with(Auth.webOperator()))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Painel de operação")))
            .andExpect(content().string(containsString("ALERT_DIGEST")))
            .andExpect(content().string(containsString("radar.procurement.v1.dlq")))
            .andReturn()
            .getResponse();
    assertThat(page.getContentType()).startsWith("text/html");
  }
}
