package br.dev.miguelsanzbr.radar.synth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CopyLoaderTest {

  @Test
  void quotesEveryTextAndLeavesNullEmpty() {
    var line = new StringBuilder();

    CopyLoader.field(line, "say \"hi\", ok");
    line.append(',');
    CopyLoader.field(line, null);
    line.append(',');
    CopyLoader.field(line, "");

    assertThat(line).hasToString("\"say \"\"hi\"\", ok\",,\"\"");
  }
}
