package br.dev.miguelsanzbr.radar.search.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.search.adapter.in.web.Snippets.Segment;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnippetsTest {

  @Test
  void noSnippetHasNoSegments() {
    assertThat(Snippets.split(null)).isEmpty();
    assertThat(Snippets.split("")).isEmpty();
  }

  @Test
  void textWithoutMarkersIsOneUnmarkedSegment() {
    assertThat(Snippets.split("compra de papel"))
        .containsExactly(new Segment("compra de papel", false));
  }

  @Test
  void theMatchedTermsAreMarked() {
    assertThat(Snippets.split("compra de <b>papel</b> e <b>tinta</b>"))
        .isEqualTo(
            List.of(
                new Segment("compra de ", false),
                new Segment("papel", true),
                new Segment(" e ", false),
                new Segment("tinta", true)));
  }

  @Test
  void markersAtTheEdgesLeaveNoEmptySegment() {
    assertThat(Snippets.split("<b>papel</b>")).containsExactly(new Segment("papel", true));
  }

  @Test
  void aClosingMarkerWithoutOpeningStaysUnmarked() {
    assertThat(Snippets.split("a</b>b"))
        .containsExactly(new Segment("a", false), new Segment("b", false));
  }

  @Test
  void anyOtherTagIsPlainText() {
    assertThat(Snippets.split("<script>alert(1)</script> <B>x</B>"))
        .containsExactly(new Segment("<script>alert(1)</script> <B>x</B>", false));
  }
}
