package br.dev.miguelsanzbr.radar.architecture.fixture.outbound;

import org.springframework.web.client.RestClient;

/** Uses the Spring client outside the gateway. */
public class UsesRestClient {

  RestClient client;
}
