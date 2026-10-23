package br.dev.miguelsanzbr.radar.shared;

/**
 * The schema registry did not answer while a consumer decoded an Avro record. It is an outage, not
 * a bad record: the error handlers retry it without limit and never dead-letter it. The Avro value
 * deserializer leaves {@link #MARKER} (and the bytes in Base64) as the text of such a record, and
 * each listener rejects the text with {@link #rejectIfMarked}.
 */
public final class RegistryUnavailableException extends RuntimeException {

  public static final String MARKER = "avro-registry-unavailable:";

  public RegistryUnavailableException() {
    super("the schema registry did not answer; the record is retried");
  }

  /**
   * Throws when the value is the marker of a record that could not be decoded for lack of the
   * registry.
   */
  public static void rejectIfMarked(String value) {
    if (value != null && value.startsWith(MARKER)) {
      throw new RegistryUnavailableException();
    }
  }
}
