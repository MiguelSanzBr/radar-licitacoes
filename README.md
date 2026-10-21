# Radar de Licitações

Plataforma que coleta as licitações publicadas no PNCP (Portal Nacional de Contratações Públicas), mantém o histórico dos editais, oferece busca textual em português e envia e-mail quando um edital atende a uma busca salva.

## Estado

Em construção. O projeto está no marco M0 (sonda de coleta e fundação do repositório). Nenhuma funcionalidade para usuário está pronta ainda.

## Arquitetura

Monólito modular com Spring Modulith e arquitetura hexagonal por contexto, com CQRS: escrita em JPA e leitura em jOOQ. Os eventos saem por Transactional Outbox e são publicados no Kafka, e consumidores idempotentes alimentam projeções reconstruíveis. As decisões e os motivos estão nas ADRs em `docs/adr/`.

## Como rodar

Por enquanto, o alvo disponível é a verificação completa do build:

```
make verify
```

É preciso JDK 25 e Docker (os testes de integração sobem containers com Testcontainers).

Imagem: 393 MB (medido por scripts/image-size.sh em 2026-10-20).

## Números

<!-- bench:start -->
Nenhum número publicado ainda. Todo número desta seção vem de `make bench` ou `make slo`, com data, hardware e rótulo real/sintético.
<!-- bench:end -->

## Fora do escopo

- Nuvem permanente.
- Provedor de e-mail externo.
- MFA e WebAuthn.
- Contratos, atas e itens até a sonda provar viabilidade.
- gRPC e GraphQL (camada 4), só se houver folga.

## Licença

Distribuído sob a licença MIT. Veja o arquivo [LICENSE](LICENSE).
