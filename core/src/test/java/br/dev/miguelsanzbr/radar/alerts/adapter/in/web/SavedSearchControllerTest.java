package br.dev.miguelsanzbr.radar.alerts.adapter.in.web;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.alerts.application.EraseSubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyGuard;
import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyGuard.Result;
import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyGuard.Stored;
import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyMismatchException;
import br.dev.miguelsanzbr.radar.alerts.application.ManageSavedSearchUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.SavedSearchNotFoundException;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertFilters;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedQuery;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearch;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearchLimitExceededException;
import br.dev.miguelsanzbr.radar.alerts.domain.SubscriberErasedException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(controllers = {SavedSearchController.class, MeController.class})
@Import({ProblemAdvice.class, HeaderCurrentSubscriber.class})
@ActiveProfiles("test")
class SavedSearchControllerTest {

  private static final UUID OWNER = UUID.fromString("0198a3a0-0000-7000-8000-000000000001");
  private static final UUID SEARCH_ID = UUID.fromString("0198a3a0-0000-7000-8000-000000000002");
  private static final String BODY =
      """
      {"name":"Notebooks","query":"notebook","frequency":"DAILY",
       "filters":{"uf":"SP","minValue":"10","open":true}}
      """;

  @Autowired MockMvc mvc;
  @MockitoBean ManageSavedSearchUseCase manage;
  @MockitoBean EraseSubscriberUseCase erase;
  @MockitoBean IdempotencyGuard guard;

  private static SavedSearch search() {
    return new SavedSearch(
        SEARCH_ID,
        OWNER,
        "Notebooks",
        new SavedQuery("notebook", new AlertFilters("SP", null, null, "10.00", null, true)),
        Frequency.DAILY,
        true,
        Instant.parse("2026-10-22T12:00:00Z"));
  }

  private static MockHttpServletRequestBuilder create(String key, String body) {
    var request =
        post("/api/v1/saved-searches")
            .headers(Auth.asSubscriber(OWNER))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  private void guardRunsTheAction(boolean replayed) {
    when(guard.run(eq(OWNER), eq("key-12345"), any(), any()))
        .thenAnswer(call -> new Result(call.<Supplier<Stored>>getArgument(3).get(), replayed));
  }

  @Test
  void createReturns201WithLocationAndTheSavedSearch() throws Exception {
    guardRunsTheAction(false);
    when(manage.create(eq(OWNER), eq("Notebooks"), any(), eq(Frequency.DAILY)))
        .thenReturn(search());

    mvc.perform(create("key-12345", BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/saved-searches/" + SEARCH_ID))
        .andExpect(header().doesNotExist("Idempotent-Replayed"))
        .andExpect(jsonPath("$.id").value(SEARCH_ID.toString()))
        .andExpect(jsonPath("$.filters.uf").value("SP"))
        .andExpect(jsonPath("$.filters.minValue").value("10.00"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(conforms());
    verify(manage)
        .create(
            OWNER,
            "Notebooks",
            new SavedQuery("notebook", new AlertFilters("SP", null, null, "10.00", null, true)),
            Frequency.DAILY);
  }

  @Test
  void createWithoutFiltersUsesNone() throws Exception {
    guardRunsTheAction(false);
    when(manage.create(eq(OWNER), any(), eq(new SavedQuery("q", AlertFilters.NONE)), any()))
        .thenReturn(search());

    mvc.perform(
            create(
                "key-12345",
                """
                {"name":"n","query":"q","frequency":"INSTANT"}
                """))
        .andExpect(status().isCreated())
        .andExpect(conforms());
  }

  @Test
  void replayedResponseCarriesTheReplayHeader() throws Exception {
    when(guard.run(eq(OWNER), eq("key-12345"), any(), any()))
        .thenReturn(
            new Result(
                new Stored(
                    201,
                    """
                    {"id":"%s","name":"Notebooks","query":"notebook","frequency":"DAILY",\
                    "active":true,"createdAt":"2026-10-22T12:00:00Z"}\
                    """
                        .formatted(SEARCH_ID)),
                true));

    mvc.perform(create("key-12345", BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(SEARCH_ID.toString()))
        .andExpect(conforms());
  }

  @Test
  void equalBodiesWithDifferentFieldOrderHashTheSame() throws Exception {
    guardRunsTheAction(false);
    when(manage.create(any(), any(), any(), any())).thenReturn(search());
    var hashes = ArgumentCaptor.forClass(byte[].class);

    mvc.perform(create("key-12345", BODY)).andExpect(status().isCreated());
    mvc.perform(
            create(
                "key-12345",
                """
                {"frequency":"DAILY","filters":{"open":true,"minValue":"10","uf":"SP"},
                 "query":"notebook","name":"Notebooks"}
                """))
        .andExpect(status().isCreated());

    verify(guard, times(2)).run(any(), any(), hashes.capture(), any());
    assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
  }

  @Test
  void missingIdempotencyKeyIs400Problem() throws Exception {
    mvc.perform(create(null, BODY))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/validation.md"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Requisição inválida"));
  }

  @Test
  void shortIdempotencyKeyAndInvalidBodyAre400() throws Exception {
    mvc.perform(create("short", BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
    mvc.perform(
            create(
                "key-12345",
                """
                {"name":"","query":"q","frequency":"DAILY"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").exists());
    mvc.perform(create("key-12345", "{not json")).andExpect(status().isBadRequest());
  }

  @Test
  void withoutSubscriberHeaderIs401Problem() throws Exception {
    mvc.perform(
            post("/api/v1/saved-searches")
                .headers(Auth.anonymous())
                .header("Idempotency-Key", "key-12345")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/unauthorized.md"))
        .andExpect(conforms());
  }

  @Test
  void idempotencyMismatchIs422WithItsOwnType() throws Exception {
    when(guard.run(any(), any(), any(), any())).thenThrow(new IdempotencyMismatchException());

    mvc.perform(create("key-12345", BODY))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/idempotency-mismatch.md"))
        .andExpect(jsonPath("$.title").value("Chave de idempotência reutilizada"))
        .andExpect(conforms());
  }

  @Test
  void limitReachedAndDomainRuleViolationsAre422Validation() throws Exception {
    guardRunsTheAction(false);
    when(manage.create(any(), any(), any(), any()))
        .thenThrow(new SavedSearchLimitExceededException(20));
    mvc.perform(create("key-12345", BODY))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value("limit of 20 active saved searches reached"))
        .andExpect(conforms());

    mvc.perform(
            create(
                "key-12345",
                """
                {"name":"n","query":"q","frequency":"DAILY","filters":{"minValue":"9","maxValue":"1"}}
                """))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/validation.md"))
        .andExpect(conforms());
  }

  @Test
  void erasedSubscriberIs403() throws Exception {
    guardRunsTheAction(false);
    when(manage.create(any(), any(), any(), any())).thenThrow(new SubscriberErasedException());

    mvc.perform(create("key-12345", BODY)).andExpect(status().isForbidden()).andExpect(conforms());
  }

  @Test
  void getReturnsTheOwnSearch() throws Exception {
    when(manage.get(OWNER, SEARCH_ID)).thenReturn(search());

    mvc.perform(get("/api/v1/saved-searches/{id}", SEARCH_ID).headers(Auth.asSubscriber(OWNER)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Notebooks"))
        .andExpect(conforms());
  }

  @Test
  void getOfAnotherSubscribersSearchIs404Problem() throws Exception {
    when(manage.get(OWNER, SEARCH_ID)).thenThrow(new SavedSearchNotFoundException());

    mvc.perform(get("/api/v1/saved-searches/{id}", SEARCH_ID).headers(Auth.asSubscriber(OWNER)))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type")
                .value(
                    "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/not-found.md"))
        .andExpect(conforms());
  }

  @Test
  void getWithMalformedIdIs400() throws Exception {
    mvc.perform(get("/api/v1/saved-searches/not-a-uuid").headers(Auth.asSubscriber(OWNER)))
        .andExpect(status().isBadRequest())
        .andExpect(conforms());
  }

  @Test
  void deleteDeactivatesAndReturns204() throws Exception {
    mvc.perform(delete("/api/v1/saved-searches/{id}", SEARCH_ID).headers(Auth.asSubscriber(OWNER)))
        .andExpect(status().isNoContent())
        .andExpect(conforms());

    verify(manage).delete(OWNER, SEARCH_ID);
  }

  @Test
  void deleteOfAnotherSubscribersSearchIs404() throws Exception {
    doThrow(new SavedSearchNotFoundException()).when(manage).delete(OWNER, SEARCH_ID);

    mvc.perform(delete("/api/v1/saved-searches/{id}", SEARCH_ID).headers(Auth.asSubscriber(OWNER)))
        .andExpect(status().isNotFound())
        .andExpect(conforms());
  }

  @Test
  void deleteMeErasesTheCurrentSubscriber() throws Exception {
    mvc.perform(delete("/api/v1/me").headers(Auth.asSubscriber(OWNER)))
        .andExpect(status().isNoContent())
        .andExpect(conforms());

    verify(erase).erase(OWNER);
  }

  @Test
  void deleteMeWithoutSubscriberIs401() throws Exception {
    mvc.perform(delete("/api/v1/me").headers(Auth.anonymous()))
        .andExpect(status().isUnauthorized())
        .andExpect(conforms());
  }

  @Test
  void aStrayIllegalArgumentIsNotTurnedIntoAProblem() {
    when(manage.get(OWNER, SEARCH_ID)).thenThrow(new IllegalArgumentException("bug"));

    assertThatThrownBy(
            () ->
                mvc.perform(
                    get("/api/v1/saved-searches/{id}", SEARCH_ID)
                        .headers(Auth.asSubscriber(OWNER))))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }
}
