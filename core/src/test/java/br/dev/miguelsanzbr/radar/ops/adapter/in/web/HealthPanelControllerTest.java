package br.dev.miguelsanzbr.radar.ops.adapter.in.web;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.ops.application.HealthPanelUseCase;
import br.dev.miguelsanzbr.radar.ops.domain.Checkpoint;
import br.dev.miguelsanzbr.radar.ops.domain.Component;
import br.dev.miguelsanzbr.radar.ops.domain.ConsumerLag;
import br.dev.miguelsanzbr.radar.ops.domain.Cursor;
import br.dev.miguelsanzbr.radar.ops.domain.DlqCount;
import br.dev.miguelsanzbr.radar.ops.domain.FlagState;
import br.dev.miguelsanzbr.radar.ops.domain.HealthPanel;
import br.dev.miguelsanzbr.radar.ops.domain.IngestionRun;
import br.dev.miguelsanzbr.radar.ops.domain.OutboxBacklog;
import br.dev.miguelsanzbr.radar.ops.domain.Status;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthPanelControllerTest {

  private static final Instant AT = Instant.parse("2026-10-22T12:00:00Z");

  private final HealthPanelUseCase useCase = mock(HealthPanelUseCase.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new HealthPanelController(useCase)).build();

  private static HealthPanel panel() {
    return new HealthPanel(
        Status.DEGRADED,
        List.of(
            new Component("outbox", Status.UP, "pendentes 3"),
            new Component("dlq", Status.DEGRADED, null)),
        new OutboxBacklog(3, 1.5, 0),
        List.of(
            new IngestionRun("INCREMENTAL", 6, "SUCCEEDED", 10, 2, 3, 5, AT.minusSeconds(60), AT),
            new IngestionRun("INCREMENTAL", 12, "RUNNING", 0, 0, 0, 0, AT, null)),
        List.of(new Cursor(6, AT)),
        List.of(new ConsumerLag("search-projector", "radar.procurement.v1", 7)),
        List.of(new DlqCount("radar.procurement.v1.dlq", "search-projector", 2)),
        List.of(new Checkpoint("search", 42L, AT)),
        List.of(new FlagState("ALERT_DIGEST", true)));
  }

  @Test
  void thePanelIsTheContractsHealthPanel() throws Exception {
    when(useCase.read()).thenReturn(panel());

    mvc.perform(get("/ops/health-panel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DEGRADED"))
        .andExpect(jsonPath("$.components[0].name").value("outbox"))
        .andExpect(jsonPath("$.outbox.pending").value(3))
        .andExpect(jsonPath("$.lastRuns[0].finishedAt").value("2026-10-22T12:00:00Z"))
        .andExpect(jsonPath("$.consumers[0].lag").value(7))
        .andExpect(jsonPath("$.dlq[0].unreprocessed").value(2))
        .andExpect(jsonPath("$.dlq[0].consumerGroup").value("search-projector"))
        .andExpect(jsonPath("$.flags[0].enabled").value(true))
        .andExpect(conforms());
  }

  @Test
  void aSectionThatCouldNotBeReadIsAbsent() throws Exception {
    when(useCase.read())
        .thenReturn(
            new HealthPanel(
                Status.DOWN,
                List.of(new Component("outbox", Status.DOWN, "IllegalStateException")),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

    mvc.perform(get("/ops/health-panel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outbox").doesNotExist())
        .andExpect(conforms());
  }
}
