# Digikoppeling ebMS2 Adapter

> Container-native implementatie van de **Digikoppeling Koppelvlakstandaard ebMS2 v3.3.2**
> conform **ISO 15000 / OASIS ebXML Messaging Services v2.0**.
>
> Java 21 · Spring Boot 3.4 · Apache CXF 4 · Maven multi-module · Zero-Trust microservices

---

## Architectuuroverzicht

```
                        ┌──────────────────────────────────────────────────────┐
                        │              ebms-internal (Docker Network)           │
                        │                                                        │
 Externe partner  ──────►  [Ingress/mTLS Proxy]  ──────►  ebms-orchestrator    │
 (ebMS2 SOAP)           │   X-Forwarded-Client-OIN          :8080/services     │
                        │                                         │              │
                        │                     ┌───────────────────┤              │
                        │                     ▼                   ▼              │
                        │               cpa-service         crypto-service       │
                        │                  :8081                :8082            │
                        │               (CPA REST)          (XML-DSig/Enc)      │
                        │                   │                    │               │
                        │            postgres-cpa        postgres-crypto          │
                        │               :5434                 :5435              │
                        │                                                        │
                        │  ebms-orchestrator ──► RabbitMQ ──► [Backoffice]      │
                        │                         :5672                          │
                        │   postgres-orchestrator                                │
                        │          :5433                                         │
                        └──────────────────────────────────────────────────────┘
```

---

## Modulestructuur

```
ebms-adapter/
├── pom.xml                          ← Parent POM (ebms-parent 1.0.0-SNAPSHOT)
├── docker-compose.yml               ← Volledige stack inclusief databases
├── .gitignore
├── README.md
│
├── ebms-common/                     ← Gedeelde bibliotheek (geen executable)
│   ├── pom.xml
│   └── src/main/java/nl/logius/ebms/common/
│       ├── EbmsCommon.java          ← Constanten (namespace, versie)
│       ├── model/ebxml/             ← [Fase 2] ebXML SOAP DTO's
│       ├── model/cpa/               ← [Fase 2] CPA domeinmodel
│       ├── model/amqp/              ← [Fase 2] AMQP berichtmodellen
│       ├── util/                    ← [Fase 2] OIN-validator, XML-utils
│       └── exception/               ← [Fase 2] Gedeelde uitzonderingen
│
├── ebms-orchestrator/               ← Spring Boot :8080
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/nl/logius/ebms/orchestrator/
│       │   ├── EbmsOrchestratorApplication.java
│       │   └── config/
│       │       └── RabbitMqConfig.java   ← AMQP topologie (queues + bindings)
│       └── resources/
│           ├── application.yml
│           └── db/migration/
│               └── V1__init_messages.sql ← ebms_message tabel
│
├── cpa-service/                     ← Spring Boot :8081
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/nl/logius/ebms/cpa/
│       │   └── CpaServiceApplication.java
│       └── resources/
│           ├── application.yml
│           └── db/migration/
│               └── V1__init_cpa.sql      ← CPA, partijen, certificaten
│
└── crypto-service/                  ← Spring Boot :8082
    ├── pom.xml
    ├── Dockerfile
    └── src/main/
        ├── java/nl/logius/ebms/crypto/
        │   └── CryptoServiceApplication.java
        └── resources/
            ├── application.yml
            └── db/migration/
                └── V1__init_keystores.sql ← Sleutel-metadata + auditlog
```

---

## Tech Stack

| Component           | Technologie                                  | Versie   |
|---------------------|----------------------------------------------|----------|
| Java runtime        | Eclipse Temurin (JRE alpine)                 | 21 LTS   |
| Framework           | Spring Boot                                  | 3.4.1    |
| SOAP / MTOM         | Apache CXF                                   | 4.0.4    |
| XML-DSig / XML-Enc  | Apache Santuario (xmlsec)                    | 3.0.4    |
| PKI / Crypto        | Bouncy Castle                                | 1.78.1   |
| Persistence         | Spring Data JPA + PostgreSQL                 | 16       |
| DB-migraties        | Flyway                                       | 10.x     |
| Messaging           | Spring AMQP + RabbitMQ                       | 3.13     |
| Build               | Maven                                        | 3.9      |
| Code-generatie      | Lombok + MapStruct                           | actueel  |
| Containerisatie     | Docker Compose + multi-stage Dockerfile      | –        |

---

## Snelstart – Docker Compose

### Vereisten
- Docker Desktop of Docker Engine + Compose Plugin
- Maven 3.9+ en Java 21 (alleen voor lokale bouw zonder Docker)

### Opstarten

```bash
# Bouw alle images en start de stack
cd ebms-adapter/
docker compose up --build

# Of op de achtergrond:
docker compose up --build -d
```

### Services na opstarten

| Service              | URL / poort                            | Beschrijving              |
|----------------------|----------------------------------------|---------------------------|
| ebms-orchestrator    | http://localhost:8080/services         | SOAP ebMS2 endpoint       |
| ebms-orchestrator    | http://localhost:8080/actuator/health  | Health check              |
| cpa-service          | http://localhost:8081/actuator/health  | CPA REST API health       |
| crypto-service       | http://localhost:8082/actuator/health  | Crypto service health     |
| RabbitMQ Management  | http://localhost:15672                 | UI (ebms_amqp/secret_amqp)|
| PostgreSQL orch.     | localhost:5433                         | DB ebms_orchestrator      |
| PostgreSQL CPA       | localhost:5434                         | DB ebms_cpa               |
| PostgreSQL crypto    | localhost:5435                         | DB ebms_crypto            |

### Stoppen en reset

```bash
# Stoppen (volumes behouden)
docker compose down

# Stoppen + alle data verwijderen
docker compose down -v
```

---

## Lokaal bouwen (zonder Docker)

```bash
cd ebms-adapter/

# Volledig project bouwen
mvn clean install

# Alleen orchestrator (inclusief afhankelijkheden)
mvn -pl ebms-orchestrator -am clean package

# Snel bouwen (zonder tests)
mvn clean package -P fast
```

---

## Digikoppeling Profielen

| Profiel    | Beschrijving                                    |
|------------|-------------------------------------------------|
| `osb-be`   | Best Effort                                     |
| `osb-rm`   | Reliable Messaging (ACK + retry)                |
| `osb-be-s` | Best Effort + ondertekening (XML-DSig)          |
| `osb-rm-s` | Reliable Messaging + ondertekening              |
| `osb-be-e` | Best Effort + encryptie (XML-Enc)               |
| `osb-rm-e` | Reliable Messaging + ondertekening + encryptie  |

---

## Database-schema overzicht

### ebms-orchestrator (postgres-orchestrator:5433)
- `ebms_message` – bericht-state en lifecycle (Reliable Messaging)

### cpa-service (postgres-cpa:5434)
- `collaboration_protocol_agreement` – CPA-documenten (XML)
- `cpa_party` – partij-informatie met OIN
- `cpa_delivery_channel` – technische kanaalconfiguratie
- `partner_certificate` – PKI-certificaten per partner

### crypto-service (postgres-crypto:5435)
- `key_pair_metadata` – sleutelmetadata (geen sleutels zelf!)
- `crypto_audit_log` – append-only operatie-auditlog

---

## Faseroadmap

| Fase | Inhoud                                                                 | Status  |
|------|------------------------------------------------------------------------|---------|
| 1    | Maven multi-module structuur + Docker Compose + database-schema's      | ✅ Gereed |
| 2    | ebXML DTO's, CPA-domeinmodel, AMQP-berichtmodellen, OIN-validatie      | Gepland |
| 3    | SOAP endpoint implementatie (Apache CXF), CPA-REST API                 | Gepland |
| 4    | XML-DSig ondertekening/verificatie (Santuario + Bouncy Castle)         | Gepland |
| 5    | XML-Enc encryptie/decryptie, KeyStore-beheer                           | Gepland |
| 6    | Reliable Messaging (ACK, retry, duplicate suppression)                 | Gepland |
| 7    | auditor-service, Kubernetes manifesten, mTLS Ingress                   | Gepland |

---

## Security

- **Zero-Trust**: elke service heeft een eigen geïsoleerde database
- **Non-root containers**: alle Dockerfiles gebruiken een `ebms` systeemgebruiker
- **Sleutels nooit in Git**: PKCS12-bestanden staan in `crypto-keystores` Docker volume
- **Audit-trail**: alle crypto-operaties worden gelogd in `crypto_audit_log`

---

## Licentie

Intern gebruik – Logius / Ministerie van Binnenlandse Zaken en Koninkrijksrelaties.
