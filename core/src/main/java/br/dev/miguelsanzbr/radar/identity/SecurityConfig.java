package br.dev.miguelsanzbr.radar.identity;

import br.dev.miguelsanzbr.radar.identity.adapter.in.web.Problems;
import br.dev.miguelsanzbr.radar.identity.adapter.in.web.RateLimitFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Two chains. The API ({@code /api}, {@code /ops}, {@code /actuator}) is a stateless JWT resource
 * server without CSRF that answers 401/403 as problem+json. The pages log in through OIDC, keep a
 * session and require the CSRF token, which scripts read from the {@code XSRF-TOKEN} cookie and
 * send back in {@code X-XSRF-TOKEN}. Roles come from the Keycloak claim {@code realm_access.roles}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

  static final String CSP =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
          + " frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

  @Bean
  @Order(1)
  SecurityFilterChain apiChain(HttpSecurity http) {
    var roles = new JwtAuthenticationConverter();
    roles.setJwtGrantedAuthoritiesConverter(SecurityConfig::realmRoles);
    return http.securityMatcher("/api/**", "/ops/**", "/actuator/**")
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        "/api/v1/saved-searches/**", "/api/v1/me/**", "/api/v1/api-keys/**")
                    .hasRole("supplier")
                    .requestMatchers("/ops/**")
                    .hasRole("operator")
                    .requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .hasRole("operator")
                    .requestMatchers(
                        HttpMethod.GET, RateLimitFilter.PUBLIC_READS.toArray(String[]::new))
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            server ->
                server
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(roles))
                    .authenticationEntryPoint((request, response, e) -> unauthorized(response)))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint((request, response, e) -> unauthorized(response))
                    .accessDeniedHandler(
                        (request, response, e) ->
                            Problems.write(
                                response,
                                403,
                                "forbidden",
                                "Acesso negado",
                                "O papel do usuário não permite esta rota.")))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .headers(SecurityConfig::headers)
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain pageChain(HttpSecurity http) {
    return http.authorizeHttpRequests(
            requests ->
                requests
                    .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/assinatura", "/buscas/**")
                    .hasRole("supplier")
                    .requestMatchers(HttpMethod.GET, "/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            login ->
                login.userInfoEndpoint(
                    userInfo -> userInfo.userAuthoritiesMapper(SecurityConfig::withRealmRoles)))
        .logout(Customizer.withDefaults())
        .csrf(csrf -> csrf.spa())
        .headers(SecurityConfig::headers)
        .build();
  }

  /** ID tokens must also come from the configured issuer; built lazily, on the first login. */
  @Bean
  static OidcIdTokenDecoderFactory idTokenDecoders(@Value("${radar.oidc.issuer}") String issuer) {
    var factory = new OidcIdTokenDecoderFactory();
    factory.setJwtValidatorFactory(
        registration ->
            new DelegatingOAuth2TokenValidator<>(
                new OidcIdTokenValidator(registration), new JwtIssuerValidator(issuer)));
    return factory;
  }

  private static void headers(HeadersConfigurer<HttpSecurity> headers) {
    // nosniff and HSTS (secure requests only) are already on by default.
    headers
        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
        .frameOptions(frame -> frame.deny());
  }

  /** Keycloak realm roles as {@code ROLE_<name>}; a token without them has no role. */
  static List<GrantedAuthority> realmRoles(ClaimAccessor token) {
    var access = token.getClaimAsMap("realm_access");
    if (access == null || !(access.get("roles") instanceof Collection<?> names)) {
      return List.of();
    }
    return names.stream()
        .<GrantedAuthority>map(name -> new SimpleGrantedAuthority("ROLE_" + name))
        .toList();
  }

  /** The browser login keeps its own authorities and adds the realm roles of the ID token. */
  static Collection<GrantedAuthority> withRealmRoles(
      Collection<? extends GrantedAuthority> authorities) {
    var mapped = new ArrayList<GrantedAuthority>(authorities);
    for (var authority : authorities) {
      if (authority instanceof OidcUserAuthority oidc) {
        mapped.addAll(realmRoles(oidc.getIdToken()));
      }
    }
    return mapped;
  }

  private static void unauthorized(HttpServletResponse response) throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer");
    Problems.write(response, 401, "unauthorized", "Não autenticado", "Token ausente ou inválido.");
  }
}
