package br.dev.miguelsanzbr.radar.search.domain;

/** Checks of the id a cursor carries. */
final class CursorIds {

  private CursorIds() {}

  /** A blank id or one with a control character (NUL included) matches no row and breaks SQL. */
  static void requireUsable(String id) {
    if (id == null || id.isBlank() || id.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidCursorException("id is blank or has control characters");
    }
  }
}
