package br.dev.miguelsanzbr.radar.probe;

import java.io.IOException;

/** Entry point of the PNCP probe jar. */
public enum PncpProbe {
  ; // no constants: a holder of static methods that cannot be instantiated

  public static void main(String[] args) throws IOException {
    Cli.exitOnFailure(args, System.out, System.err, System::exit);
  }
}
