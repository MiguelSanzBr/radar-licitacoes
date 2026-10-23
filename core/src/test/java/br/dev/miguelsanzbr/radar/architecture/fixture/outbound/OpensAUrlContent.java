package br.dev.miguelsanzbr.radar.architecture.fixture.outbound;

import java.net.URL;

/** Reads the content of a URL. */
public class OpensAUrlContent {

  Object open(URL url) throws java.io.IOException {
    return url.getContent();
  }
}
