package br.dev.miguelsanzbr.radar.architecture.fixture.outbound;

import org.springframework.web.client.RestTemplate;

/** Uses the older Spring client outside the gateway. */
public class UsesRestTemplate {

  RestTemplate client;
}
