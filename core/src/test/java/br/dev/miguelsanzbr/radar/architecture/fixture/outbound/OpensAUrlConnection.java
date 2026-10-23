package br.dev.miguelsanzbr.radar.architecture.fixture.outbound;

import java.net.URL;

/** Opens a connection from a URL. */
public class OpensAUrlConnection {

  void open(URL url) throws java.io.IOException {
    url.openConnection();
  }
}
