workspace "Radar de Licitações" "Coleta licitações do PNCP, indexa e avisa fornecedores por busca salva." {

    model {
        supplier = person "Fornecedor" "Busca licitações e recebe alertas de buscas salvas."
        operator = person "Operador" "Opera a coleta, a fila de falhas e os feature flags."

        pncp = softwareSystem "PNCP API" "Portal Nacional de Contratações Públicas. Fonte oficial e externa dos dados." "External"
        # Um só elemento para o servidor de e-mail: em produção seria um SMTP real, no compose é o Mailpit.
        smtp = softwareSystem "Mailpit / SMTP" "Recebe os e-mails de alerta (Mailpit no ambiente local)." "External"
        # Fronteira externa ao sistema, mas roda como container no compose (PR 5.1).
        keycloak = softwareSystem "Keycloak" "Provedor de identidade: autenticação OIDC, JWT e papéis." "External"

        radar = softwareSystem "Radar de Licitações" "Coleta, projeta, busca e alerta." {
            app = container "Aplicação (monólito modular)" "Spring Boot 4.1 / Spring Modulith 2.1, Java 25. Camadas 1 e 2 em um único processo." "Java" {
                ingest = component "ingest" "Coleta (write side): backfill, sync incremental, fechamento de licitações vencidas." "Módulo Modulith"
                outbox = component "outbox" "Relay genérico do outbox (SKIP LOCKED) que publica eventos no Kafka." "Módulo Modulith"
                search = component "search" "Busca (read side): projetor, consulta full-text, API REST e interface web." "Módulo Modulith"
                alerts = component "alerts" "Alertas: casamento de buscas salvas, resumo diário, envio de e-mail." "Módulo Modulith"
                identity = component "identity" "Validação de JWT, RBAC, chaves de API e limite de taxa." "Módulo Modulith"
                ops = component "ops" "Painel de saúde, reprocesso da DLQ e feature flags." "Módulo Modulith"
            }
            db = container "PostgreSQL 18" "Um schema por contexto: ingest, outbox, search, alerts, identity." "PostgreSQL" "Database"
            kafka = container "Kafka (KRaft)" "Tópicos radar.procurement.v1, radar.alert.v1 e respectivas DLQs." "Apache Kafka 4.x" "Queue"
            redis = container "Redis" "Limite de taxa e cache de curta duração." "Redis 8" "Database"
            lgtm = container "otel-lgtm" "Coleta e visualiza métricas, logs e traces (Grafana, Loki, Tempo, Prometheus)." "grafana/otel-lgtm"
        }

        # Nível 1
        supplier -> radar "Busca licitações e gerencia buscas salvas" "HTTPS"
        operator -> radar "Opera coleta, DLQ e flags" "HTTPS"
        radar -> pncp "Coleta licitações" "HTTPS/JSON"
        radar -> smtp "Envia alertas" "SMTP"
        radar -> keycloak "Autentica usuários e valida tokens" "OIDC / JWT"

        # Nível 2
        supplier -> app "Usa a interface web e a API REST" "HTTPS"
        operator -> app "Usa os endpoints de operação" "HTTPS"
        app -> db "Lê e grava (JPA no write side, jOOQ no read side)" "JDBC"
        app -> kafka "Publica e consome eventos" "Kafka protocol"
        app -> redis "Contadores de limite de taxa" "RESP"
        app -> smtp "Envia e-mails de alerta" "SMTP"
        app -> keycloak "Valida JWT (JWKS)" "HTTPS"
        app -> pncp "Coleta licitações (Resilience4j)" "HTTPS/JSON"
        app -> lgtm "Exporta métricas, logs e traces" "OTLP"

        # Nível 3 (módulos, só para leitura do mapa de dependências)
        ingest -> outbox "Grava evento na mesma transação" "API pública do módulo"
        outbox -> kafka "Publica" "Kafka protocol"
        kafka -> search "Entrega eventos" "Inbox idempotente"
        kafka -> alerts "Entrega eventos" "Inbox idempotente"
    }

    views {
        systemContext radar "C4-1-contexto" "Nível 1: o sistema e quem se relaciona com ele." {
            include *
            autolayout lr
        }

        container radar "C4-2-containers" "Nível 2: contêineres do sistema." {
            include *
            autolayout lr
        }

        component app "C4-3-modulos" "Módulos da aplicação (apoio ao ADR-0003)." {
            include *
            autolayout lr
        }

        styles {
            element "Person" {
                shape Person
                background #08427b
                color #ffffff
            }
            element "Software System" {
                background #1168bd
                color #ffffff
            }
            element "External" {
                background #8a8a8a
                color #ffffff
            }
            element "Container" {
                background #438dd5
                color #ffffff
            }
            element "Component" {
                background #85bbf0
                color #000000
            }
            element "Database" {
                shape Cylinder
            }
            element "Queue" {
                shape Pipe
            }
        }

    }
}
