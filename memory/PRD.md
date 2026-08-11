# PRD – Digikoppeling ebMS2 Adapter

## Probleemstelling
Vervangen van een verouderde monolithische Digikoppeling ebMS2 codebase door een
schaalbare, container-native microservices-architectuur op basis van Zero-Trust.

## Doelstelling
Bouwen van een moderne ebMS2 adapter conform:
- Digikoppeling Koppelvlakstandaard ebMS2 **v3.3.2**
- **ISO 15000** / OASIS ebXML Messaging Services v2.0

---

## Architectuur

### Services
| Service             | Poort | Technologie                     | Database            |
|---------------------|-------|---------------------------------|---------------------|
| ebms-orchestrator   | 8080  | Spring Boot 3.4 + Apache CXF 4 | postgres-orch :5433 |
| cpa-service         | 8081  | Spring Boot 3.4 + Caffeine      | postgres-cpa  :5434 |
| crypto-service      | 8082  | Spring Boot 3.4 + Santuario     | postgres-crypto :5435|
| RabbitMQ            | 5672  | RabbitMQ 3.13                   | –                   |

### Tech Stack
- **Java:** 21 LTS (Eclipse Temurin)
- **Framework:** Spring Boot 3.4.1
- **SOAP:** Apache CXF 4.0.4
- **XML Security:** Apache Santuario 3.0.4
- **PKI:** Bouncy Castle 1.78.1
- **Database:** PostgreSQL 16 + Spring Data JPA
- **Migraties:** Flyway 10.x
- **Messaging:** Spring AMQP + RabbitMQ 3.13
- **Build:** Maven 3.9 (multi-module)
- **Code-generatie:** Lombok 1.18.36 + MapStruct 1.6.3

---

## Gebruikerskeuzes
- Build tool: **Maven**
- Message Broker: **RabbitMQ**
- DB-migraties: **Flyway**
- Projectlocatie: `/app/ebms-adapter/`
- Scope Fase 1: Maven multi-module structuur + docker-compose.yml

---

## Wat is geïmplementeerd

### Fase 1 (voltooid – februari 2026)
- [x] Maven multi-module parent POM (`ebms-parent 1.0.0-SNAPSHOT`)
  - Spring Boot 3.4.1 BOM import
  - Apache CXF 4.0.4, Santuario 3.0.4, Bouncy Castle 1.78.1
  - Lombok 1.18.36 + MapStruct 1.6.3 (annotation processing)
  - JaCoCo, Surefire, Failsafe, Enforcer plugins
  - Profielen: `docker`, `fast`
- [x] Module POMs:
  - `ebms-common` – gedeelde bibliotheek
  - `ebms-orchestrator` – SOAP endpoint + RabbitMQ
  - `cpa-service` – CPA REST API + Caffeine cache
  - `crypto-service` – XML-DSig/Enc + Bouncy Castle
- [x] Application.java voor elk Spring Boot service
- [x] `RabbitMqConfig.java` – AMQP exchange + 4 queues (incl. DLQ)
- [x] `application.yml` voor elk service (extern configureerbaar via env vars)
- [x] Flyway migratie V1 voor alle drie databases
- [x] Multi-stage Dockerfiles voor alle drie services
- [x] `docker-compose.yml` met 3x PostgreSQL + RabbitMQ + 3 services
- [x] `.gitignore` (PKI-bestanden uitgesloten)
- [x] `README.md` met architectuurdiagram en quickstart

---

## Geprioriteerde Backlog

### P0 – Fase 2: Domeinmodel & DTO's
- [ ] ebXML SOAP-envelop DTO's (`model/ebxml/`)
  - `EbxmlEnvelope`, `MessageHeader`, `MessageInfo`, `PartyId`, `Service`
- [ ] CPA domeinmodel (`model/cpa/`)
  - `CollaborationProtocolAgreement`, `PartyInfo`, `DeliveryChannel`
- [ ] AMQP berichtmodellen (`model/amqp/`)
  - `EbmsInboundMessage`, `EbmsOutboundMessage`, `AuditEvent`
- [ ] OIN-validator (ISO 6523) + EbmsException hiërarchie

### P1 – Fase 3: SOAP endpoint & CPA REST API
- [ ] Apache CXF SOAP endpoint implementatie
- [ ] Ping/Echo service (ISO 15000-2)
- [ ] CPA CRUD REST API (POST/GET/DELETE)
- [ ] OIN-extractie uit `X-Forwarded-Client-OIN` header

### P1 – Fase 4: XML Security
- [ ] XML-DSig ondertekening (RSA-SHA256)
- [ ] XML-DSig verificatie
- [ ] XML-C14N (Exclusive Canonicalization)

### P2 – Fase 5: Encryptie & KeyStore
- [ ] XML-Enc encryptie (AES-256-GCM)
- [ ] XML-Enc decryptie
- [ ] KeyStore-beheer API

### P2 – Fase 6: Reliable Messaging
- [ ] ACK-verwerking
- [ ] Retry-mechanisme (configureerbaar per profiel)
- [ ] Duplicate suppression (sliding window)

### P3 – Fase 7: Productie-klaar
- [ ] auditor-service (append-only event store)
- [ ] Kubernetes manifesten (Deployment, Service, Ingress, NetworkPolicy)
- [ ] mTLS Ingress Proxy configuratie
- [ ] Prometheus/Grafana monitoring
- [ ] Integratie tests (Testcontainers)

---

## Digikoppeling Profielen
`osb-be` | `osb-rm` | `osb-be-s` | `osb-rm-s` | `osb-be-e` | `osb-rm-e`

## Referenties
- [Digikoppeling Koppelvlakstandaard ebMS2 v3.3.2](https://www.logius.nl/diensten/digikoppeling)
- [OASIS ebXML Messaging Services v2.0](https://www.oasis-open.org/standards#ebxmlmsg)
- [Apache CXF 4.x](https://cxf.apache.org/)
- [Apache Santuario](https://santuario.apache.org/)
