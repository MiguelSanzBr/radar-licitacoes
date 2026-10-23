package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The messages of the search routes are in Portuguese whatever {@code Accept-Language} says. The
 * parameter validation builds its messages from the request locale before the handler runs, so the
 * locale is set here, ahead of it; the servlet restores the previous one when the request ends.
 */
@Configuration(proxyBeanMethods = false)
class FixedLocaleConfiguration implements WebMvcConfigurer, HandlerInterceptor {

  private static final Locale PORTUGUESE = Locale.of("pt", "BR");

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(this)
        .addPathPatterns("/api/v1/procurements/**", "/api/v1/stats", "/api/v1/organs");
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    LocaleContextHolder.setLocale(PORTUGUESE);
    return true;
  }
}
