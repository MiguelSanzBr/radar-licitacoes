package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.alerts.Auth;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

class ApiKeyLifecycleIT extends PostgresIT {

  private static final String DOCS =
      "https://github.com/miguelsanzbr/radar-licitacoes/blob/main/docs/problems/";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;

  private String create(String subject) throws Exception {
    var body =
        mvc.perform(post("/api/v1/api-keys").with(Auth.supplier(subject)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JSON.readTree(body).get("key").asString();
  }

  @Test
  void theKeyIsShownOnceAndOnlyItsHashIsStored() throws Exception {
    var key = create("kc-1");

    assertThat(key).matches("rk_[0-9a-f]{8}_[A-Za-z0-9_-]{43}");
    var stored =
        jdbc.sql(
                "SELECT count(*) FROM identity.api_key"
                    + " WHERE owner_subject = 'kc-1' AND key_hash = sha256(convert_to(:key, 'UTF8'))"
                    + " AND prefix = :prefix AND tier = 'free'")
            .param("key", key)
            .param("prefix", key.substring(3, 11))
            .query(Long.class)
            .single();
    assertThat(stored).isEqualTo(1);
  }

  @Test
  void aSixthActiveKeyIsRefusedAndRevokingFreesASlot() throws Exception {
    var first = create("kc-1");
    for (var i = 0; i < 4; i++) {
      create("kc-1");
    }

    mvc.perform(post("/api/v1/api-keys").with(Auth.supplier("kc-1")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value(DOCS + "validation.md"));
    mvc.perform(delete("/api/v1/api-keys/" + first.substring(3, 11)).with(Auth.supplier("kc-1")))
        .andExpect(status().isNoContent());
    create("kc-1");
  }

  @Test
  void anotherSubjectsPrefixAndARevokedKeyAreNotFound() throws Exception {
    var prefix = create("kc-1").substring(3, 11);

    mvc.perform(delete("/api/v1/api-keys/" + prefix).with(Auth.supplier("kc-2")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(DOCS + "not-found.md"));
    mvc.perform(delete("/api/v1/api-keys/" + prefix).with(Auth.supplier("kc-1")))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/api/v1/api-keys/" + prefix).with(Auth.supplier("kc-1")))
        .andExpect(status().isNotFound());
  }

  @Test
  void parallelRequestsOfOneSubjectCreateExactlyTheLimit() throws Exception {
    try (var pool = Executors.newFixedThreadPool(12)) {
      var statuses =
          IntStream.range(0, 12)
              .mapToObj(
                  i ->
                      pool.submit(
                          (Callable<Integer>)
                              () ->
                                  mvc.perform(post("/api/v1/api-keys").with(Auth.supplier("kc-1")))
                                      .andReturn()
                                      .getResponse()
                                      .getStatus()))
              .toList();
      var created = 0;
      for (var status : statuses) {
        created += status.get() == 201 ? 1 : 0;
      }

      assertThat(created).isEqualTo(5);
    }
  }
}
