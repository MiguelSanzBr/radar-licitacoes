package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = DeprecationInterceptorTest.Legacy.class)
@Import({
  DeprecationConfiguration.class,
  SecurityConfig.class,
  DeprecationInterceptorTest.Legacy.class
})
class DeprecationInterceptorTest {

  static final String DEPRECATED = "2026-10-01T00:00:00Z";
  static final String SUNSET = "2027-04-01T00:00:00Z";

  /** Stands in for a route a v2 has replaced; the real API has none yet. */
  @RestController
  static class Legacy {

    @GetMapping("/api/v1/stats")
    @DeprecatedApi(deprecated = DEPRECATED, sunset = SUNSET)
    String old() {
      return "old";
    }

    @GetMapping("/api/v1/organs")
    String current() {
      return "current";
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void anAnnotatedRouteAnnouncesItsDeprecationAndSunset() throws Exception {
    mvc.perform(get("/api/v1/stats"))
        .andExpect(status().isOk())
        .andExpect(header().string("Deprecation", "@1790812800"))
        .andExpect(header().string("Sunset", "Thu, 01 Apr 2027 00:00:00 GMT"));
  }

  @Test
  void aRouteWithoutTheAnnotationSendsNeitherHeader() throws Exception {
    mvc.perform(get("/api/v1/organs"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Deprecation"))
        .andExpect(header().doesNotExist("Sunset"));
  }

  @Test
  void aRequestForAStaticResourceSendsNeitherHeader() throws Exception {
    mvc.perform(get("/assets/missing.txt"))
        .andExpect(header().doesNotExist("Deprecation"))
        .andExpect(header().doesNotExist("Sunset"));
  }
}
