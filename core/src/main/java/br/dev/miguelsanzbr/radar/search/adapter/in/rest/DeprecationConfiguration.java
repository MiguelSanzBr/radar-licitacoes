package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Adds {@code Deprecation} (RFC 9745) and {@code Sunset} (RFC 8594) to the routes marked {@link
 * DeprecatedApi}. The headers are set before the handler runs, because a large body may commit the
 * response before any later hook.
 */
@Configuration(proxyBeanMethods = false)
class DeprecationConfiguration implements WebMvcConfigurer, HandlerInterceptor {

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this);
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (handler instanceof HandlerMethod method
        && method.getMethodAnnotation(DeprecatedApi.class) instanceof DeprecatedApi api) {
      response.setHeader("Deprecation", "@" + Instant.parse(api.deprecated()).getEpochSecond());
      response.setDateHeader("Sunset", Instant.parse(api.sunset()).toEpochMilli());
    }
    return true;
  }
}
