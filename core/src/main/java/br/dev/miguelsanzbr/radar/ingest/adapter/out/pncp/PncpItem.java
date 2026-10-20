package br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** The fields of a PNCP procurement item the domain uses; datetimes stay text until mapped. */
@JsonIgnoreProperties(ignoreUnknown = true)
record PncpItem(
    String numeroControlePNCP,
    int situacaoCompraId,
    String situacaoCompraNome,
    Organ orgaoEntidade,
    Unit unidadeOrgao,
    int modalidadeId,
    String modalidadeNome,
    Integer modoDisputaId,
    String modoDisputaNome,
    Legal amparoLegal,
    boolean srp,
    BigDecimal valorTotalEstimado,
    BigDecimal valorTotalHomologado,
    String dataAberturaProposta,
    String dataEncerramentoProposta,
    String dataPublicacaoPncp,
    String dataAtualizacaoGlobal,
    String objetoCompra) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Organ(String cnpj, String razaoSocial) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Unit(
      String codigoUnidade,
      String nomeUnidade,
      String ufSigla,
      String municipioNome,
      String codigoIbge) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Legal(int codigo, String nome) {}
}
