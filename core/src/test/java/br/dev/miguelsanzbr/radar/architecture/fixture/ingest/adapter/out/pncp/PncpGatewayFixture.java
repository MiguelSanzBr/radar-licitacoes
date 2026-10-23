package br.dev.miguelsanzbr.radar.architecture.fixture.ingest.adapter.out.pncp;

import java.net.http.HttpClient;

/** The one place allowed to call out. */
public class PncpGatewayFixture {

  HttpClient http;
}
