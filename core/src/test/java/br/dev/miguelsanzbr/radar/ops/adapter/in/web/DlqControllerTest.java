package br.dev.miguelsanzbr.radar.ops.adapter.in.web;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.ops.application.DlqNotFoundException;
import br.dev.miguelsanzbr.radar.ops.application.ReprocessDlqUseCase;
import br.dev.miguelsanzbr.radar.ops.application.ReprocessDlqUseCase.Result;
import br.dev.miguelsanzbr.radar.ops.application.ReprocessInterruptedException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DlqControllerTest {

  private final ReprocessDlqUseCase useCase = mock(ReprocessDlqUseCase.class);
  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new DlqController(useCase))
          .setControllerAdvice(new OpsProblemAdvice())
          .build();

  @Test
  void aRunIsAcceptedWithTheCountOfRepublishedMessages() throws Exception {
    when(useCase.reprocess("radar.procurement.v1.dlq", null)).thenReturn(new Result(3, true));

    mvc.perform(post("/ops/dlq/radar.procurement.v1.dlq/reprocess"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.count").value(3))
        .andExpect(jsonPath("$.more").value(true))
        .andExpect(conforms());
  }

  @Test
  void theIdParameterReprocessesOneMessage() throws Exception {
    when(useCase.reprocess("radar.alert.v1.dlq", 42L)).thenReturn(new Result(1, false));

    mvc.perform(post("/ops/dlq/radar.alert.v1.dlq/reprocess").param("id", "42"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(conforms());
  }

  @Test
  void anUnknownTopicOrIdIsAProblemNotFound() throws Exception {
    when(useCase.reprocess("radar.other.v1.dlq", null))
        .thenThrow(new DlqNotFoundException("no such dead-letter topic: radar.other.v1.dlq"));

    mvc.perform(post("/ops/dlq/radar.other.v1.dlq/reprocess"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/not-found.md"))
        .andExpect(jsonPath("$.detail").value("no such dead-letter topic: radar.other.v1.dlq"))
        .andExpect(conforms());
  }

  @Test
  void aMarkThatFailedAfterTheSendIsStillAnAcceptedRunWithMoreToDo() throws Exception {
    when(useCase.reprocess("radar.alert.v1.dlq", null))
        .thenReturn(new Result(1, true, "java.lang.IllegalStateException: db down"));

    mvc.perform(post("/ops/dlq/radar.alert.v1.dlq/reprocess"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.more").value(true))
        .andExpect(conforms());
  }

  @Test
  void aBrokenRepublishIsAServiceUnavailableProblemWithTheCountDone() throws Exception {
    when(useCase.reprocess("radar.alert.v1.dlq", null))
        .thenThrow(new ReprocessInterruptedException(2, "IllegalStateException: broker down"));

    mvc.perform(post("/ops/dlq/radar.alert.v1.dlq/reprocess"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/reprocess-interrupted.md"))
        .andExpect(jsonPath("$.count").value(2))
        .andExpect(conforms());
  }

  @Test
  void aMalformedIdIsABadRequestProblem() throws Exception {
    mvc.perform(post("/ops/dlq/radar.alert.v1.dlq/reprocess").param("id", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }
}
