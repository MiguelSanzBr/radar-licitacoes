package br.dev.miguelsanzbr.radar.architecture.fixture.outbound;

import java.net.URL;

/** Reads a URL as a stream. */
public class OpensAUrlStream {

  void open(URL url) throws java.io.IOException {
    url.openStream();
  }
}
