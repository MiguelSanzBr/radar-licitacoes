package br.dev.miguelsanzbr.radar.alerts;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Callers of the secured routes, as the security chain sees them after authentication. */
public final class Auth {

  private static final SimpleGrantedAuthority SUPPLIER =
      new SimpleGrantedAuthority("ROLE_supplier");
  private static final SimpleGrantedAuthority OPERATOR =
      new SimpleGrantedAuthority("ROLE_operator");

  private Auth() {}

  /** A bearer token that does not decode: the contract wants the header, the chain answers 401. */
  public static HttpHeaders anonymous() {
    var headers = new HttpHeaders();
    headers.setBearerAuth("test");
    return headers;
  }

  /** API caller with the supplier role; the subject is the identity provider's user id. */
  public static RequestPostProcessor supplier(String subject) {
    return jwt().jwt(token -> token.subject(subject)).authorities(SUPPLIER);
  }

  public static RequestPostProcessor operator() {
    return jwt().jwt(token -> token.subject("operator")).authorities(OPERATOR);
  }

  /** Browser session of a supplier after the OIDC login. */
  public static RequestPostProcessor webSupplier(String subject, String email) {
    return oidcLogin()
        .idToken(
            token -> token.subject(subject).claim("email", email).claim("email_verified", true))
        .authorities(SUPPLIER);
  }

  public static RequestPostProcessor webOperator() {
    return oidcLogin().idToken(token -> token.subject("operator")).authorities(OPERATOR);
  }
}
