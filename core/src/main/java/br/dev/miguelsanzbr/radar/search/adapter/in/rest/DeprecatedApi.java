package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a route that a newer version replaces: the response carries {@code Deprecation} and {@code
 * Sunset}. No route uses it yet; the API is v1. It lives in the search adapter, the only one with
 * public routes today; move it to a shared web package when another module needs it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DeprecatedApi {

  /** When the route was deprecated, an ISO-8601 instant: sent as {@code @<epoch seconds>}. */
  String deprecated();

  /** The shutdown instant, ISO-8601: sent as an HTTP date in {@code Sunset}. */
  String sunset();
}
