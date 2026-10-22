package br.dev.miguelsanzbr.radar.alerts.adapter;

import static br.dev.miguelsanzbr.radar.api.OpenApiConformance.conforms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.alerts.adapter.in.scheduler.PurgeJob;
import br.dev.miguelsanzbr.radar.alerts.domain.Sha256;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class AlertsFlowIT extends PostgresIT {

  private static final String BODY =
      """
      {"name":"Notebooks","query":"notebook","frequency":"DAILY","filters":{"uf":"SP"}}
      """;

  @Autowired MockMvc mvc;
  @Autowired PurgeJob purgeJob;

  /** Consent from a logged-in browser session whose subject is the lower-cased e-mail. */
  private ResultActions consent(String email) throws Exception {
    return mvc.perform(
        post("/assinatura")
            .with(Auth.webSupplier(email.toLowerCase(java.util.Locale.ROOT), email))
            .with(csrf())
            .param("consent", "on"));
  }

  /** Consents and follows the confirmation link; returns the token subject. */
  private String subscribe(String email) throws Exception {
    consent(email).andExpect(status().isAccepted());
    mvc.perform(get("/assinatura/confirmar").param("token", mailer.lastToken()))
        .andExpect(status().isOk());
    return email.toLowerCase(java.util.Locale.ROOT);
  }

  private UUID idOf(String email) {
    return jdbc.sql("SELECT id FROM alerts.subscriber WHERE email = CAST(:e AS citext)")
        .param("e", email)
        .query(UUID.class)
        .single();
  }

  private static MockHttpServletRequestBuilder create(String subject, String key, String body) {
    return post("/api/v1/saved-searches")
        .with(Auth.supplier(subject))
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private long count(String table) {
    return jdbc.sql("SELECT count(*) FROM alerts." + table).query(Long.class).single();
  }

  @Test
  void registrationStoresOnlyTheTokenHashAndTheLinkWorksOnce() throws Exception {
    consent("Fulano@Example.com").andExpect(status().isAccepted());
    var raw = mailer.lastToken();

    var storedHash =
        jdbc.sql("SELECT token_hash FROM alerts.verification_token").query(byte[].class).single();
    assertThat(storedHash).isEqualTo(Sha256.hash(raw));
    assertThat(HexFormat.of().formatHex(storedHash)).doesNotContain(raw);
    assertThat(mailer.sent.getFirst()[0]).isEqualTo("fulano@example.com");
    assertThat(
            jdbc.sql("SELECT verified_at IS NULL FROM alerts.subscriber")
                .query(Boolean.class)
                .single())
        .isTrue();

    mvc.perform(get("/assinatura/confirmar").param("token", raw)).andExpect(status().isOk());
    mvc.perform(get("/assinatura/confirmar").param("token", raw))
        .andExpect(status().isBadRequest());

    assertThat(
            jdbc.sql("SELECT verified_at IS NOT NULL FROM alerts.subscriber")
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void expiredTokenDoesNotVerify() throws Exception {
    consent("fulano@example.com").andExpect(status().isAccepted());
    jdbc.sql("UPDATE alerts.verification_token SET expires_at = now() - interval '1 second'")
        .update();

    mvc.perform(get("/assinatura/confirmar").param("token", mailer.lastToken()))
        .andExpect(status().isBadRequest());

    assertThat(
            jdbc.sql("SELECT verified_at IS NULL FROM alerts.subscriber")
                .query(Boolean.class)
                .single())
        .isTrue();
  }

  @Test
  void consentIsRequired() throws Exception {
    mvc.perform(
            post("/assinatura")
                .with(Auth.webSupplier("fulano@example.com", "fulano@example.com"))
                .with(csrf()))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value("consent is required"));

    assertThat(count("subscriber")).isZero();
    assertThat(mailer.sent).isEmpty();
  }

  @Test
  void verifiedAddressThatDiffersOnlyByCaseIs409() throws Exception {
    subscribe("Fulano@Example.com");

    consent("fulano@example.com")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409));

    assertThat(count("subscriber")).isEqualTo(1);
    assertThat(mailer.sent).hasSize(1);
  }

  @Test
  void reRegisteringAnUnverifiedAddressReplacesTheTokenAndReturns202() throws Exception {
    consent("Fulano@Example.com").andExpect(status().isAccepted());
    var oldToken = mailer.lastToken();

    consent("fulano@example.com").andExpect(status().isAccepted());
    var newToken = mailer.lastToken();

    assertThat(newToken).isNotEqualTo(oldToken);
    assertThat(mailer.sent).hasSize(2);
    assertThat(count("subscriber")).isEqualTo(1);
    assertThat(count("verification_token")).isEqualTo(1);
    mvc.perform(get("/assinatura/confirmar").param("token", oldToken))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/assinatura/confirmar").param("token", newToken)).andExpect(status().isOk());
  }

  @Test
  void sameKeyAndBodyReplaysTheFirstResponse() throws Exception {
    var owner = subscribe("fulano@example.com");

    var first =
        mvc.perform(create(owner, "key-0001", BODY))
            .andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replayed"))
            .andExpect(conforms())
            .andReturn();
    var second =
        mvc.perform(create(owner, "key-0001", BODY))
            .andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(conforms())
            .andReturn();

    assertThat(second.getResponse().getContentAsString())
        .isEqualTo(first.getResponse().getContentAsString());
    assertThat(second.getResponse().getHeader("Location"))
        .isEqualTo(first.getResponse().getHeader("Location"));
    assertThat(count("saved_search")).isEqualTo(1);
  }

  @Test
  void sameKeyWithAnotherBodyIs422AndCreatesNothing() throws Exception {
    var owner = subscribe("fulano@example.com");
    mvc.perform(create(owner, "key-0001", BODY)).andExpect(status().isCreated());

    mvc.perform(create(owner, "key-0001", BODY.replace("Notebooks", "Other")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("idempotency-mismatch.md")))
        .andExpect(conforms());

    assertThat(count("saved_search")).isEqualTo(1);
  }

  @Test
  void keysAreScopedBySubscriber() throws Exception {
    var one = subscribe("fulano@example.com");
    var two = subscribe("ciclano@example.com");

    mvc.perform(create(one, "key-0001", BODY)).andExpect(status().isCreated());
    mvc.perform(create(two, "key-0001", BODY))
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Idempotent-Replayed"));

    assertThat(count("saved_search")).isEqualTo(2);
  }

  @Test
  void keysOlderThanADayAreIgnored() throws Exception {
    var owner = subscribe("fulano@example.com");
    mvc.perform(create(owner, "key-0001", BODY)).andExpect(status().isCreated());
    jdbc.sql("UPDATE alerts.idempotency_key SET created_at = now() - interval '25 hours'").update();

    mvc.perform(create(owner, "key-0001", BODY.replace("Notebooks", "Other")))
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Idempotent-Replayed"));

    assertThat(count("saved_search")).isEqualTo(2);
  }

  @Test
  void twoIdenticalRequestsAtTheSameTimeCreateOneSavedSearch() throws Exception {
    var owner = subscribe("fulano@example.com");
    var barrier = new CyclicBarrier(2);
    Callable<MvcResult> call =
        () -> {
          barrier.await();
          return mvc.perform(create(owner, "key-race-1", BODY)).andReturn();
        };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(call);
      var second = pool.submit(call);

      var responses = List.of(first.get().getResponse(), second.get().getResponse());

      assertThat(responses).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(201));
      assertThat(responses.stream().filter(r -> "true".equals(r.getHeader("Idempotent-Replayed"))))
          .hasSize(1);
      assertThat(responses.get(0).getContentAsString())
          .isEqualTo(responses.get(1).getContentAsString());
    }
    assertThat(count("saved_search")).isEqualTo(1);
  }

  @Test
  void twentyFirstActiveSearchIsRejected() throws Exception {
    var owner = subscribe("fulano@example.com");
    for (var i = 0; i < 20; i++) {
      mvc.perform(create(owner, "key-%04d".formatted(i), BODY)).andExpect(status().isCreated());
    }

    mvc.perform(create(owner, "key-0020", BODY))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(conforms());

    assertThat(count("saved_search")).isEqualTo(20);
    assertThat(count("idempotency_key")).isEqualTo(20);
  }

  @Test
  void anotherSubscribersSearchIsAlways404() throws Exception {
    var owner = subscribe("fulano@example.com");
    var stranger = subscribe("ciclano@example.com");
    var location =
        mvc.perform(create(owner, "key-0001", BODY))
            .andReturn()
            .getResponse()
            .getHeader("Location");

    mvc.perform(get(location).with(Auth.supplier(stranger)))
        .andExpect(status().isNotFound())
        .andExpect(conforms());
    mvc.perform(delete(location).with(Auth.supplier(stranger)))
        .andExpect(status().isNotFound())
        .andExpect(conforms());
    mvc.perform(get(location).with(Auth.supplier(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Notebooks"))
        .andExpect(jsonPath("$.filters.uf").value("SP"))
        .andExpect(conforms());
    mvc.perform(delete(location).with(Auth.supplier(owner)))
        .andExpect(status().isNoContent())
        .andExpect(conforms());
    mvc.perform(get(location).with(Auth.supplier(owner))).andExpect(status().isNotFound());
  }

  @Test
  void withoutConsentIs403ConsentRequiredAndWithoutCredentials401() throws Exception {
    mvc.perform(create("never-consented", "key-0001", BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("consent-required.md")))
        .andExpect(conforms());
    mvc.perform(
            post("/api/v1/saved-searches")
                .headers(Auth.anonymous())
                .header("Idempotency-Key", "key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(conforms());
    mvc.perform(delete("/api/v1/me").headers(Auth.anonymous()))
        .andExpect(status().isUnauthorized())
        .andExpect(conforms());
    assertThat(count("subscriber")).isZero();
  }

  @Test
  void consentLinksTheTokenSubjectToTheSubscriber() throws Exception {
    var subject = subscribe("fulano@example.com");

    assertThat(
            jdbc.sql("SELECT external_subject FROM alerts.subscriber").query(String.class).single())
        .isEqualTo(subject);
  }

  @Test
  void anotherAccountCannotTakeOverAPendingAddress() throws Exception {
    mvc.perform(
            post("/assinatura")
                .with(Auth.webSupplier("first-account", "fulano@example.com"))
                .with(csrf())
                .param("consent", "on"))
        .andExpect(status().isAccepted());

    consent("fulano@example.com")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("e-mail already registered"));

    assertThat(
            jdbc.sql("SELECT external_subject FROM alerts.subscriber").query(String.class).single())
        .isEqualTo("first-account");
    assertThat(mailer.sent).hasSize(1);
  }

  @Test
  void oneAccountCannotHoldTwoSubscribers() throws Exception {
    subscribe("fulano@example.com");

    mvc.perform(
            post("/assinatura")
                .with(Auth.webSupplier("fulano@example.com", "outro@example.com"))
                .with(csrf())
                .param("consent", "on"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("this account already has a subscriber"));

    assertThat(count("subscriber")).isEqualTo(1);
  }

  @Test
  void deleteMeAnonymizesDeactivatesAndCleansUp() throws Exception {
    var owner = subscribe("fulano@example.com");
    var other = subscribe("ciclano@example.com");
    var ownerId = idOf(owner);
    var otherId = idOf(other);
    mvc.perform(create(owner, "key-0001", BODY)).andExpect(status().isCreated());
    mvc.perform(create(owner, "key-0002", BODY.replace("Notebooks", "Other")))
        .andExpect(status().isCreated());
    mvc.perform(create(other, "key-0001", BODY)).andExpect(status().isCreated());
    jdbc.sql(
            """
            INSERT INTO alerts.alert_delivery (saved_search_id, procurement_id, matched_at, sent_at)
            SELECT id, 'p-sent', now(), now() FROM alerts.saved_search
            UNION ALL
            SELECT id, 'p-unsent', now(), NULL FROM alerts.saved_search
            """)
        .update();
    var token = mailer.lastToken();

    mvc.perform(delete("/api/v1/me").with(Auth.supplier(owner)))
        .andExpect(status().isNoContent())
        .andExpect(conforms());
    mvc.perform(delete("/api/v1/me").with(Auth.supplier(owner))).andExpect(status().isNoContent());

    var row =
        jdbc.sql(
                "SELECT email, deleted_at IS NOT NULL AS deleted, external_subject"
                    + " FROM alerts.subscriber WHERE id = :i")
            .param("i", ownerId)
            .query(
                (rs, n) ->
                    rs.getString("email")
                        + "/"
                        + rs.getBoolean("deleted")
                        + "/"
                        + rs.getString("external_subject"))
            .single();
    assertThat(row).isEqualTo("deleted-" + ownerId + "@invalid/true/null");
    assertThat(
            jdbc.sql("SELECT count(*) FROM alerts.saved_search WHERE subscriber_id = :i AND active")
                .param("i", ownerId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql(
                    "SELECT name || '|' || query_text FROM alerts.saved_search"
                        + " WHERE subscriber_id = :i AND NOT active")
                .param("i", ownerId)
                .query(String.class)
                .list())
        .containsExactly("(apagada)|", "(apagada)|");
    assertThat(
            jdbc.sql(
                    "SELECT procurement_id FROM alerts.alert_delivery d JOIN alerts.saved_search s"
                        + " ON s.id = d.saved_search_id WHERE s.subscriber_id = :i")
                .param("i", ownerId)
                .query(String.class)
                .list())
        .containsExactlyInAnyOrder("p-sent", "p-sent");
    assertThat(
            jdbc.sql("SELECT count(*) FROM alerts.idempotency_key WHERE subscriber_id = :i")
                .param("i", ownerId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM alerts.saved_search WHERE subscriber_id = :i AND active")
                .param("i", otherId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(
            jdbc.sql("SELECT count(*) FROM alerts.idempotency_key WHERE subscriber_id = :i")
                .param("i", otherId)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    mvc.perform(create(owner, "key-0003", BODY))
        .andExpect(status().isForbidden())
        .andExpect(conforms());
    mvc.perform(get("/assinatura/confirmar").param("token", token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void erasedEmailCanRegisterAgain() throws Exception {
    var owner = subscribe("fulano@example.com");
    mvc.perform(delete("/api/v1/me").with(Auth.supplier(owner))).andExpect(status().isNoContent());

    consent("fulano@example.com").andExpect(status().isAccepted());

    assertThat(count("subscriber")).isEqualTo(2);
  }

  @Test
  void dailyPurgeDropsOldUnverifiedSubscribersAndStaleKeys() throws Exception {
    var verified = subscribe("verified@example.com");
    mvc.perform(create(verified, "key-0001", BODY)).andExpect(status().isCreated());
    consent("stale@example.com").andExpect(status().isAccepted());
    consent("recent@example.com").andExpect(status().isAccepted());
    jdbc.sql(
            "UPDATE alerts.subscriber SET created_at = now() - interval '8 days'"
                + " WHERE email = 'stale@example.com'")
        .update();
    jdbc.sql("UPDATE alerts.idempotency_key SET created_at = now() - interval '25 hours'").update();

    purgeJob.purge();

    assertThat(jdbc.sql("SELECT email FROM alerts.subscriber").query(String.class).list())
        .containsExactlyInAnyOrder("verified@example.com", "recent@example.com");
    assertThat(count("idempotency_key")).isZero();
    assertThat(count("saved_search")).isEqualTo(1);
  }
}
