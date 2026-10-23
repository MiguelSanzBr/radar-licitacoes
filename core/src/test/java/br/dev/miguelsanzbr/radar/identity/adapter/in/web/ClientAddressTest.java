package br.dev.miguelsanzbr.radar.identity.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressTest {

  @ParameterizedTest
  @CsvSource({
    "203.0.113.7, 203.0.113.7",
    "255.255.255.255, 255.255.255.255",
    "2001:db8:1:2:aaaa:bbbb:cccc:dddd, 2001:db8:1:2::/64",
    "2001:DB8:1:2::1, 2001:db8:1:2::/64",
    "2001:db8:1:3::1, 2001:db8:1:3::/64",
    "::1, 0:0:0:0::/64",
    "::ffff:203.0.113.7, 203.0.113.7",
    "::ffff:cb00:7107, 203.0.113.7"
  })
  void anIpLiteralBecomesItsClientKey(String literal, String key) {
    assertThat(ClientAddress.normalize(literal)).isEqualTo(key);
  }

  @Test
  void clientsOfTheSameV6SlashSixtyFourShareAKeyButNeighbourNetworksDoNot() {
    assertThat(ClientAddress.normalize("2001:db8:1:2::1"))
        .isEqualTo(ClientAddress.normalize("2001:db8:1:2:ffff:ffff:ffff:ffff"))
        .isNotEqualTo(ClientAddress.normalize("2001:db8:1:3::1"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "not-an-ip",
        "evil.example.com",
        "256.1.1.1",
        "203.0.113.007",
        "010.0.0.1",
        "1.2.3",
        "1.2.3.4.5",
        "1.2.3.4 ",
        "fe80::1%eth0",
        "[::1]",
        "1:2:3:4:5:6:7:8:9",
        ":::",
        "abc:",
        "12345::1",
        "::g",
        ".::1",
        ".:"
      })
  void anythingElseIsNotAnAddress(String text) {
    assertThat(ClientAddress.normalize(text)).isNull();
  }

  @Test
  void aRemoteAddressThatIsNotALiteralIsUsedAsItIs() {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("unix-socket");
    request.addHeader("X-Forwarded-For", "198.51.100.9");

    assertThat(ClientAddress.of(request, false)).isEqualTo("unix-socket");
    assertThat(ClientAddress.of(request, true)).isEqualTo("198.51.100.9");
  }
}
