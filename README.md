# ledger-service

API de livro-razão (contas e transferências) em Spring Boot, correta sob
concorrência e com observabilidade de produção.

**Lacunas que fecha:** 4 (observabilidade) + "aprofundar Java" do roadmap contínuo.
**Conceitos-alvo:** concorrência e consistência, observabilidade (logs, métricas, tracing).

## Escopo (fechado)

Dentro:
- Endpoints: criar conta, consultar saldo, transferir, extrato
- Partidas dobradas: cada transferência gera débito e crédito, sem saldo negativo
- Concorrência: lock otimista (`@Version`) ou pessimista, com teste de N transferências paralelas e saldo final exato
- Idempotência de transferência via header `Idempotency-Key`
- Teste de carga com virtual threads (Java 21)
- Testcontainers (Postgres real) em todos os testes de integração
- Observabilidade: log JSON, Micrometer + Prometheus, tracing com OpenTelemetry, middleware de erro padronizado (RFC 9457 Problem Details)
- Docker Compose (app + Postgres + Prometheus + Grafana)
- GitHub Actions: build + testes

Fora:
- Autenticação (já demonstrada no Guardio)
- Múltiplas moedas, mensageria, deploy multi-região

## Stack

Java 21, Spring Boot 3, Spring Data JPA, Postgres 16, Flyway, Testcontainers,
JUnit 5, Micrometer, OpenTelemetry, Docker.

## Fases

1. Modelo de contas e lançamentos + migrations Flyway
2. Transferência com consistência + teste de concorrência
3. Idempotência
4. Observabilidade (logs, métricas, traces, Problem Details)
5. Compose com Grafana + CI + README final com diagrama

## Pronto quando

- Teste com 1000 transferências concorrentes fecha o saldo exato
- Mesma `Idempotency-Key` duas vezes = uma transferência só
- Dashboard Grafana mostra latência p95 e taxa de erro
- CI verde
