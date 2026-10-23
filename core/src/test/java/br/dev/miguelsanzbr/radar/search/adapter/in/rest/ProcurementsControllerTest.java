package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.answerConforms;
import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import br.dev.miguelsanzbr.radar.search.application.DetailUseCase;
import br.dev.miguelsanzbr.radar.search.application.ProcurementDetail;
import br.dev.miguelsanzbr.radar.search.application.ProcurementNotFoundException;
import br.dev.miguelsanzbr.radar.search.application.ReadTimeoutException;
import br.dev.miguelsanzbr.radar.search.application.SearchPage;
import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import br.dev.miguelsanzbr.radar.search.application.SituationChange;
import br.dev.miguelsanzbr.radar.search.domain.Cursor;
import br.dev.miguelsanzbr.radar.search.domain.Filters;
import br.dev.miguelsanzbr.radar.search.domain.RankedResult;
import br.dev.miguelsanzbr.radar.search.domain.SearchQuery;
import br.dev.miguelsanzbr.radar.shared.IbgeCode;
import br.dev.miguelsanzbr.radar.shared.Money;
import br.dev.miguelsanzbr.radar.shared.Uf;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ExtendWith(OutputCaptureExtension.class)
@WebMvcTest(controllers = ProcurementsController.class)
@Import({
  SearchProblemAdvice.class,
  FixedLocaleConfiguration.class,
  SecurityConfig.class,
  ProcurementsControllerTest.FixedClock.class
})
class ProcurementsControllerTest {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";
  private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
  private static final String STORED_ID = "12345678000190-1-000123/2026";
  private static final String PATH_ID = "12345678000190-1-000123-2026";
  private static final String LIST = "/api/v1/procurements";
  private static final Cursor NEXT = new Cursor.ByPublished(NOW, STORED_ID);

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClock {
    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }

  @Autowired MockMvc mvc;
  @MockitoBean SearchUseCase search;
  @MockitoBean DetailUseCase detail;

  private static RankedResult hit(String snippet, Double rank) {
    return new RankedResult(
        STORED_ID,
        "Aquisição de computadores",
        snippet,
        "Prefeitura de Teste",
        null,
        "SP",
        "São Paulo",
        "3550308",
        6,
        "PUBLISHED",
        Money.of("1500"),
        NOW.minusSeconds(3600),
        NOW.plusSeconds(3600),
        NOW.minusSeconds(7200),
        rank);
  }

  private static ProcurementDetail stored() {
    return new ProcurementDetail(
        STORED_ID,
        "Aquisição de computadores",
        "12345678000190",
        "Prefeitura de Teste",
        "1",
        "Secretaria de Saúde",
        "SP",
        "São Paulo",
        "3550308",
        6,
        "Pregão Eletrônico",
        "Aberto",
        "Lei 14.133/2021",
        true,
        "PUBLISHED",
        Money.of("1500"),
        Money.of("1400.50"),
        NOW.plusSeconds(60),
        NOW.plusSeconds(3600),
        NOW.minusSeconds(7200),
        NOW.minusSeconds(60),
        List.of(
            new SituationChange(null, "Divulgada no PNCP", NOW.minusSeconds(7200), false),
            new SituationChange("Divulgada no PNCP", "Suspensa", NOW.minusSeconds(60), true)),
        "abc123");
  }

  private SearchQuery searched() {
    var captor = ArgumentCaptor.forClass(SearchQuery.class);
    verify(search).search(captor.capture());
    return captor.getValue();
  }

  @Test
  void theListShowsEachHitWithItsDerivedPhase() throws Exception {
    when(search.search(any()))
        .thenReturn(new SearchPage(List.of(hit("<b>computadores</b>", 0.25)), null));

    mvc.perform(get(LIST + "?q=computadores"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$.limit").value(20))
        .andExpect(jsonPath("$.nextCursor").doesNotExist())
        .andExpect(jsonPath("$.items[0].id").value(PATH_ID))
        .andExpect(jsonPath("$.items[0].snippet").value("<b>computadores</b>"))
        .andExpect(jsonPath("$.items[0].rank").value(0.25))
        .andExpect(jsonPath("$.items[0].estimatedValue").value("1500.00"))
        .andExpect(jsonPath("$.items[0].proposalPhase").value("OPEN"))
        .andExpect(jsonPath("$.items[0].status").value("PUBLISHED"))
        .andExpect(header().doesNotExist("Link"))
        .andExpect(conforms());
  }

  @Test
  void queryParametersBecomeTheSearchQuery() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(), null));

    mvc.perform(
            get(
                LIST
                    + "?q=limpeza&uf=SP&ibge=3550308&modality=6&minValue=10.5&maxValue=20"
                    + "&open=true&status=SUSPENDED&limit=5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(5))
        .andExpect(conforms());

    assertThat(searched())
        .isEqualTo(
            new SearchQuery(
                "limpeza",
                new Filters(
                    Uf.SP,
                    new IbgeCode("3550308"),
                    6,
                    Money.of("10.5"),
                    Money.of("20"),
                    true,
                    "SUSPENDED"),
                null,
                5));
  }

  @Test
  void aCursorOfThePreviousPageIsDecoded() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(), null));

    mvc.perform(get(LIST + "?cursor=" + NEXT.encode()))
        .andExpect(status().isOk())
        .andExpect(conforms());

    assertThat(searched().cursor()).isEqualTo(NEXT);
  }

  @Test
  void theListEtagIsWeakAndStableForTheSamePage() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(hit(null, null)), null));

    var first = mvc.perform(get(LIST)).andReturn().getResponse().getHeader("ETag");
    var second = mvc.perform(get(LIST)).andReturn().getResponse().getHeader("ETag");
    when(search.search(any())).thenReturn(new SearchPage(List.of(), null));
    var other = mvc.perform(get(LIST)).andReturn().getResponse().getHeader("ETag");

    assertThat(first).matches("W/\"[0-9a-f]{64}\"").isEqualTo(second).isNotEqualTo(other);
  }

  @Test
  void aMatchingIfNoneMatchAnswersNotModifiedWithoutBody() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(hit(null, null)), null));
    var etag = mvc.perform(get(LIST)).andReturn().getResponse().getHeader("ETag");

    mvc.perform(get(LIST).header("If-None-Match", etag))
        .andExpect(status().isNotModified())
        .andExpect(header().string("ETag", etag))
        .andExpect(content().string(emptyString()))
        .andExpect(conforms());
  }

  @Test
  void aStaleIfNoneMatchGetsTheFullPage() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(hit(null, null)), null));

    mvc.perform(get(LIST).header("If-None-Match", "W/\"stale\""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(PATH_ID))
        .andExpect(conforms());
  }

  @Test
  void aNextCursorIsAlsoALinkThatKeepsTheOtherParameters() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(hit(null, null)), NEXT));
    var previous = new Cursor.ByPublished(NOW.minusSeconds(1), STORED_ID).encode();

    mvc.perform(get(LIST + "?uf=SP&cursor=" + previous + "&limit=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").value(NEXT.encode()))
        .andExpect(
            header()
                .string(
                    "Link",
                    "<" + LIST + "?uf=SP&limit=1&cursor=" + NEXT.encode() + ">; rel=\"next\""))
        .andExpect(conforms());
  }

  @Test
  void theLinkKeepsAnEncodedQueryAsTheClientSentIt() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(hit(null, 0.5)), NEXT));

    mvc.perform(get(URI.create(LIST + "?q=ar%20condicionado%26co")))
        .andExpect(
            header()
                .string(
                    "Link",
                    "<"
                        + LIST
                        + "?q=ar%20condicionado%26co&cursor="
                        + NEXT.encode()
                        + ">; rel=\"next\""))
        .andExpect(conforms());
  }

  @Test
  void aCursorThatIsNotOursIsAProblem() throws Exception {
    mvc.perform(get(LIST + "?cursor=adulterado"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "invalid-cursor.md"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.instance").value(LIST))
        .andExpect(conforms());

    verifyNoInteractions(search);
  }

  @Test
  void aRankCursorWithoutTextIsAProblemToo() throws Exception {
    var rank = new Cursor.ByRank(0.5, STORED_ID).encode();

    mvc.perform(get(LIST + "?cursor=" + rank))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(DOCS + "invalid-cursor.md"))
        .andExpect(conforms());
  }

  @Test
  void aLimitAboveFiftyIsAValidationProblem() throws Exception {
    mvc.perform(get(LIST + "?limit=51"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.errors[0].field").value("limit"))
        .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
        .andExpect(answerConforms());
  }

  @Test
  void aMalformedUfIsAValidationProblem() throws Exception {
    mvc.perform(get(LIST + "?uf=sp"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.errors[0].field").value("uf"))
        .andExpect(answerConforms());
  }

  @Test
  void aUfThatDoesNotExistIsAValidationProblem() throws Exception {
    mvc.perform(get(LIST + "?uf=XX"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.errors[0].field").value("uf"))
        .andExpect(conforms());
  }

  @Test
  void anIbgeCodeOfNoRegionIsAValidationProblem() throws Exception {
    mvc.perform(get(LIST + "?ibge=0000000"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("ibge"))
        .andExpect(conforms());
  }

  @Test
  void aStatusOutsideTheEnumIsAValidationProblem() throws Exception {
    mvc.perform(get(LIST + "?status=BOGUS"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.errors[0].field").value("status"))
        .andExpect(answerConforms());
  }

  @Test
  void aNonNumericLimitIsAValidationProblem() throws Exception {
    mvc.perform(get(LIST + "?limit=muitos"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("limit"))
        .andExpect(answerConforms());
  }

  @Test
  void aRangeWithMinimumAboveMaximumIsUnprocessable() throws Exception {
    mvc.perform(get(LIST + "?minValue=20&maxValue=10"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.errors[0].field").value("minValue"))
        .andExpect(conforms());

    verifyNoInteractions(search);
  }

  @Test
  void theDetailCarriesAStrongEtagAndTheHyphenatedId() throws Exception {
    when(detail.find(STORED_ID)).thenReturn(stored());

    mvc.perform(get(LIST + "/" + PATH_ID))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "ETag", "\"f5d6f8fe58348d358112ea447eae6fc04f00a46e86366b7d56f6f35eb2adff1d\""))
        .andExpect(jsonPath("$.id").value(PATH_ID))
        .andExpect(
            jsonPath("$.etag")
                .value("f5d6f8fe58348d358112ea447eae6fc04f00a46e86366b7d56f6f35eb2adff1d"))
        .andExpect(jsonPath("$.organCnpj").value("12345678000190"))
        .andExpect(jsonPath("$.srp").value(true))
        .andExpect(jsonPath("$.disputeMode").value("Aberto"))
        .andExpect(jsonPath("$.legalBasis").value("Lei 14.133/2021"))
        .andExpect(jsonPath("$.estimatedValue").value("1500.00"))
        .andExpect(jsonPath("$.awardedValue").value("1400.50"))
        .andExpect(jsonPath("$.proposalPhase").value("UPCOMING"))
        .andExpect(jsonPath("$.history.length()").value(2))
        .andExpect(jsonPath("$.history[1].from").value("Divulgada no PNCP"))
        .andExpect(jsonPath("$.history[1].to").value("Suspensa"))
        .andExpect(jsonPath("$.history[1].unexpected").value(true))
        .andExpect(conforms());
  }

  @Test
  void aMatchingStrongTagAnswersNotModifiedForTheDetail() throws Exception {
    when(detail.find(STORED_ID)).thenReturn(stored());

    mvc.perform(
            get(LIST + "/" + PATH_ID)
                .header(
                    "If-None-Match",
                    "\"f5d6f8fe58348d358112ea447eae6fc04f00a46e86366b7d56f6f35eb2adff1d\""))
        .andExpect(status().isNotModified())
        .andExpect(
            header()
                .string(
                    "ETag", "\"f5d6f8fe58348d358112ea447eae6fc04f00a46e86366b7d56f6f35eb2adff1d\""))
        .andExpect(content().string(emptyString()))
        .andExpect(conforms());
  }

  @Test
  void aDifferentTagGetsTheFullDetail() throws Exception {
    when(detail.find(STORED_ID)).thenReturn(stored());

    mvc.perform(get(LIST + "/" + PATH_ID).header("If-None-Match", "\"old\""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(PATH_ID))
        .andExpect(conforms());
  }

  @Test
  void anUnknownProcurementIsNotFound() throws Exception {
    when(detail.find(STORED_ID)).thenThrow(new ProcurementNotFoundException(STORED_ID));

    mvc.perform(get(LIST + "/" + PATH_ID))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "not-found.md"))
        .andExpect(jsonPath("$.instance").value(LIST + "/" + PATH_ID))
        .andExpect(conforms());
  }

  @Test
  void anIdOutsideThePatternIsAValidationProblem() throws Exception {
    mvc.perform(get(LIST + "/" + "x".repeat(65)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.errors[0].field").value("id"))
        .andExpect(answerConforms());

    verifyNoInteractions(detail);
  }

  @Test
  void theHistoryListsTheChangesOldestFirst() throws Exception {
    when(detail.find(STORED_ID)).thenReturn(stored());

    mvc.perform(get(LIST + "/" + PATH_ID + "/history"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].from").doesNotExist())
        .andExpect(jsonPath("$.items[0].to").value("Divulgada no PNCP"))
        .andExpect(jsonPath("$.items[0].unexpected").value(false))
        .andExpect(
            jsonPath("$.items[1].pncpUpdatedAt").value(matchesPattern("2026-10-20T11:59:00.*")))
        .andExpect(conforms());
  }

  @Test
  void theHistoryOfAnUnknownProcurementIsNotFound() throws Exception {
    when(detail.find(STORED_ID)).thenThrow(new ProcurementNotFoundException(STORED_ID));

    mvc.perform(get(LIST + "/" + PATH_ID + "/history"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(DOCS + "not-found.md"))
        .andExpect(conforms());
  }

  @Test
  void aHitWithoutOptionalFieldsOmitsThemAndHasAnUnknownPhase() throws Exception {
    var bare =
        new RankedResult(
            STORED_ID, "Objeto", null, "Órgão", null, null, null, null, 6, "REVOKED", null, null,
            null, NOW, null);
    when(search.search(any())).thenReturn(new SearchPage(List.of(bare), null));

    mvc.perform(get(LIST))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].estimatedValue").doesNotExist())
        .andExpect(jsonPath("$.items[0].proposalOpeningAt").doesNotExist())
        .andExpect(jsonPath("$.items[0].proposalClosingAt").doesNotExist())
        .andExpect(jsonPath("$.items[0].rank").doesNotExist())
        .andExpect(jsonPath("$.items[0].proposalPhase").value("UNKNOWN"))
        .andExpect(jsonPath("$.items[0].status").value("REVOKED"))
        .andExpect(conforms());
  }

  @Test
  void aMinimumValueAloneIsAValidRange() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(), null));

    mvc.perform(get(LIST + "?minValue=10")).andExpect(status().isOk()).andExpect(conforms());

    assertThat(searched().filters().minValue()).isEqualTo(Money.of("10"));
    assertThat(searched().filters().maxValue()).isNull();
  }

  @Test
  void aDetailWithoutOptionalValuesOmitsThem() throws Exception {
    var full = stored();
    when(detail.find(STORED_ID))
        .thenReturn(
            new ProcurementDetail(
                full.id(),
                full.objectDescription(),
                full.organCnpj(),
                full.organName(),
                null,
                null,
                null,
                null,
                null,
                full.modalityId(),
                full.modalityName(),
                null,
                null,
                false,
                "ANNULLED",
                null,
                null,
                null,
                null,
                full.publishedAt(),
                full.pncpUpdatedAt(),
                List.of(),
                "e2"));

    mvc.perform(get(LIST + "/" + PATH_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.estimatedValue").doesNotExist())
        .andExpect(jsonPath("$.awardedValue").doesNotExist())
        .andExpect(jsonPath("$.proposalOpeningAt").doesNotExist())
        .andExpect(jsonPath("$.proposalPhase").value("UNKNOWN"))
        .andExpect(jsonPath("$.history.length()").value(0))
        .andExpect(conforms());
  }

  @Test
  void theLinkEncodesWhatTheClientSentRaw() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(hit(null, 0.5)), NEXT));

    mvc.perform(get(URI.create(LIST + "?q=ação&uf=SP")))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Link",
                    "<"
                        + LIST
                        + "?q=a%C3%A7%C3%A3o&uf=SP&cursor="
                        + NEXT.encode()
                        + ">; rel=\"next\""))
        .andExpect(conforms());
  }

  @Test
  void aPlusInTheQueryStaysAPlusNotASpaceInTheLink() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(hit(null, 0.5)), NEXT));

    mvc.perform(get(URI.create(LIST + "?q=c%2B%2B")))
        .andExpect(
            header()
                .string(
                    "Link", "<" + LIST + "?q=c%2B%2B&cursor=" + NEXT.encode() + ">; rel=\"next\""));
  }

  @Test
  void aControlCharacterInTheTextIsAValidationProblem() throws Exception {
    mvc.perform(get(URI.create(LIST + "?q=a%00b")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"))
        .andExpect(jsonPath("$.errors[0].field").value("q"))
        .andExpect(conforms());

    verifyNoInteractions(search);
  }

  @Test
  void aCursorForYearTenThousandIsAProblemNotAServerError() throws Exception {
    var far =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(("v1|p|" + Long.MAX_VALUE + "|" + STORED_ID).getBytes());

    mvc.perform(get(LIST + "?cursor=" + far))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value(DOCS + "invalid-cursor.md"))
        .andExpect(conforms());
  }

  @Test
  void theValidationMessagesAreInPortugueseWhateverTheClientAccepts() throws Exception {
    mvc.perform(get(LIST + "?limit=51").header("Accept-Language", "en"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].message").value("deve ser menor que ou igual à 50"))
        .andExpect(answerConforms());
  }

  @Test
  void aListOfTagsOrAStarMatchesToo() throws Exception {
    when(search.search(any())).thenReturn(new SearchPage(List.of(hit(null, null)), null));
    var etag = mvc.perform(get(LIST)).andReturn().getResponse().getHeader("ETag");

    mvc.perform(get(LIST).header("If-None-Match", "\"other\", " + etag))
        .andExpect(status().isNotModified())
        .andExpect(conforms());
    mvc.perform(get(LIST).header("If-None-Match", "*"))
        .andExpect(status().isNotModified())
        .andExpect(conforms());
  }

  @Test
  void detailTagsFollowTheSameRules() throws Exception {
    when(detail.find(STORED_ID)).thenReturn(stored());
    var tag = "\"" + "f5d6f8fe58348d358112ea447eae6fc04f00a46e86366b7d56f6f35eb2adff1d" + "\"";

    mvc.perform(get(LIST + "/" + PATH_ID).header("If-None-Match", "\"x\", " + tag))
        .andExpect(status().isNotModified())
        .andExpect(conforms());
    mvc.perform(get(LIST + "/" + PATH_ID).header("If-None-Match", "*"))
        .andExpect(status().isNotModified())
        .andExpect(conforms());
    // weak comparison (RFC 9110): a weak copy of the tag still matches
    mvc.perform(get(LIST + "/" + PATH_ID).header("If-None-Match", "W/" + tag))
        .andExpect(status().isNotModified())
        .andExpect(conforms());
  }

  @Test
  void anUnexpectedFailureIsAFixedProblemAndLoggedOnce(CapturedOutput output) throws Exception {
    when(search.search(any())).thenThrow(new IllegalStateException("segredo interno"));

    mvc.perform(get(LIST))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "internal-error.md"))
        .andExpect(jsonPath("$.detail").value("Erro inesperado. Tente novamente mais tarde."))
        .andExpect(jsonPath("$.instance").value(LIST))
        .andExpect(content().string(not(containsString("segredo"))));

    assertThat(output.getAll().split("segredo interno", -1).length - 1).isEqualTo(1);
  }

  @Test
  void aReadThatTookTooLongIsUnavailable() throws Exception {
    when(search.search(any())).thenThrow(new ReadTimeoutException());

    mvc.perform(get(LIST))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value(DOCS + "unavailable.md"))
        .andExpect(header().string("Retry-After", "5"));
  }

  @Test
  void aFrameworkRejectionKeepsItsOwnStatusInsteadOfBecomingA500() throws Exception {
    mvc.perform(get(LIST).accept("application/xml")).andExpect(status().isNotAcceptable());
  }
}
