package br.dev.miguelsanzbr.radar.search.adapter.in.rest;

import org.springframework.http.HttpStatus;

/** Free text from the query string. */
final class Texts {

  private Texts() {}

  /** NUL and the other control characters match nothing and some break the SQL driver. */
  static String withoutControlCharacters(String field, String text) {
    if (text != null && text.chars().anyMatch(Character::isISOControl)) {
      throw new ValidationFailure(
          HttpStatus.BAD_REQUEST, field, "não pode ter caracteres de controle");
    }
    return text;
  }
}
