package br.dev.miguelsanzbr.radar.alerts;

import java.util.UUID;
import org.springframework.http.HttpHeaders;

/**
 * Headers for secured routes. The contract declares a bearer scheme, so the conformance validator
 * wants an Authorization header; the debug header carries the actual subscriber.
 */
public final class Auth {

  private Auth() {}

  public static HttpHeaders anonymous() {
    var headers = new HttpHeaders();
    headers.setBearerAuth("test");
    return headers;
  }

  public static HttpHeaders asSubscriber(UUID subscriber) {
    var headers = anonymous();
    headers.add("X-Debug-Subscriber", subscriber.toString());
    return headers;
  }
}
