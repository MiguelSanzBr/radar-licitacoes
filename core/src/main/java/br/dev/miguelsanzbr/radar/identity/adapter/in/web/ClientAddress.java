package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * The key of a client in the rate limit: an IPv4 address as it is, an IPv6 address as its /64
 * network (one subscriber gets a whole /64, so per-address keys would be free to rotate), and an
 * IPv4-mapped IPv6 address as the IPv4 address.
 */
final class ClientAddress {

  // no leading zeros: "010" is 8 for some parsers and 10 for others, so it is no literal at all
  private static final Pattern V4 =
      Pattern.compile(
          "(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
  private static final Pattern V6_CHARS = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*");

  private ClientAddress() {}

  /**
   * @param trustForwarded whether a proxy we control sits in front: only then is the client the
   *     last entry of {@code X-Forwarded-For}, the one that proxy appended (one trusted hop; the
   *     entries to its left are whatever the caller wrote). A last entry that is not an IP literal
   *     is ignored.
   */
  static String of(HttpServletRequest request, boolean trustForwarded) {
    if (trustForwarded) {
      String last = null;
      for (var lines = request.getHeaders("X-Forwarded-For"); lines.hasMoreElements(); ) {
        var line = lines.nextElement();
        last = normalize(line.substring(line.lastIndexOf(',') + 1).trim());
      }
      if (last != null) {
        return last;
      }
    }
    var remote = request.getRemoteAddr();
    var normalized = normalize(remote);
    return normalized == null ? remote : normalized;
  }

  /** The key of an IP literal, or null when the text is not one. Never asks the DNS. */
  static String normalize(String text) {
    // getByName parses without a lookup only text that is surely a literal
    var literal =
        V4.matcher(text).matches() || (text.indexOf(':') >= 0 && V6_CHARS.matcher(text).matches());
    if (!literal) {
      return null;
    }
    try {
      var address = InetAddress.getByName(text);
      if (address instanceof Inet6Address v6) {
        var b = v6.getAddress();
        return "%x:%x:%x:%x::/64"
            .formatted(
                (b[0] & 0xff) << 8 | (b[1] & 0xff),
                (b[2] & 0xff) << 8 | (b[3] & 0xff),
                (b[4] & 0xff) << 8 | (b[5] & 0xff),
                (b[6] & 0xff) << 8 | (b[7] & 0xff));
      }
      return address.getHostAddress();
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
