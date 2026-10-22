package br.dev.miguelsanzbr.radar.synth;

/** Entry point of the synthetic data jar. */
public enum SynthMain {
  ; // no constants: a holder of static methods that cannot be instantiated

  public static void main(String[] args) {
    Cli.exitOnFailure(args, System.getenv(), System.out, System.err, System::exit);
  }
}
