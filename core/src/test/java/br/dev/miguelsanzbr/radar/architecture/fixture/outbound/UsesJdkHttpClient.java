package br.dev.miguelsanzbr.radar.architecture.fixture.outbound;

import java.net.http.HttpClient;

/** Builds the JDK client outside the gateway. */
public class UsesJdkHttpClient {

  HttpClient http;
}
