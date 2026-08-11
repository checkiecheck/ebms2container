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
| Service             | Poort | Technologie                         | Database              |
|---------------------|-------|-------------------------------------|-----------------------|
| ebms-orchestrator   | 8080  | Spring Boot 3.4 + Apache CXF 4      | postgres-orch  :5433  |
| cpa-service         | 8081  | Spring Boot 3.4 + Caffeine cache    | postgres-cpa   :5434  |
| crypto-service      | 8082  | Spring Boot 3.4 + Santuario + BC    | postgres-crypto :5435 |
| RabbitMQ            | 5672  | RabbitMQ 3.13                       | –                     |

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

---

## Wat is geïmplementeerd

### Fase 1 (voltooid – februari 2026)
- [x] Maven multi-module parent POM (`ebms-parent 1.0.0-SNAPSHOT`)
- [x] Module POMs: ebms-common, ebms-orchestrator, cpa-service, crypto-service
- [x] `RabbitMqConfig.java` – AMQP exchange + 4 queues (incl. DLQ)
- [x] `application.yml` voor elk service (extern configureerbaar via env vars)
- [x] Flyway migratie V1 voor alle drie databases
- [x] Multi-stage Dockerfiles voor alle drie services
- [x] `docker-compose.yml` met 3x PostgreSQL + RabbitMQ + 3 services

### Fase 2 (voltooid – februari 2026)

#### ebms-common – Gedeelde domeinmodellen
- [x] `model/ebxml/EbxmlProfile.java` – 6 Digikoppeling-profielen enum
- [x] `model/ebxml/PartyId.java` – ebXML PartyId DTO
- [x] `model/ebxml/MessageInfo.java` – ebXML MessageInfo DTO
- [x] `model/ebxml/ServiceType.java` – ebXML Service DTO
- [x] `model/ebxml/AckRequested.java` – ebXML AckRequested DTO
- [x] `model/ebxml/EbxmlMessageHeader.java` – volledig ebXML MessageHeader DTO
- [x] `model/cpa/CpaDto.java` – CPA data transfer object
- [x] `model/cpa/PartyInfoDto.java` – partij-informatie DTO
- [x] `model/cpa/DeliveryChannelDto.java` – afleverkanaal DTO
- [x] `model/amqp/EbmsInboundMessage.java` – AMQP inbound message model
- [x] `model/amqp/EbmsOutboundMessage.java` – AMQP outbound message model
- [x] `model/amqp/AuditEvent.java` – AMQP audit event model
- [x] `util/OinValidator.java` – OIN-validatie (ISO 6523)
- [x] `exception/EbmsException.java` + CpaNotFoundException + DuplicateMessageException + XmlSecurityException

#### cpa-service – CPA REST API
- [x] JPA-entities: CpaEntity, CpaPartyEntity, PartnerCertificateEntity
- [x] Repositories: CpaRepository, CpaPartyRepository, PartnerCertificateRepository
- [x] `CpaMapper.java` – MapStruct mapper
- [x] `CpaService.java` – businesslogica met Caffeine caching
- [x] `CpaController.java` – REST CRUD + partijen + certificaten endpoints
- [x] `CpaErrorHandler.java` – RFC 9457 ProblemDetail responses

#### ebms-orchestrator – SOAP Endpoint + Reliable Messaging
- [x] JPA-entities: EbmsMessageEntity, MessageStatus (enum), MessageDirection (enum)
- [x] `EbmsMessageRepository.java` – incl. queries voor retry en expiry
- [x] `OrchestratorService.java` – bericht-lifecycle, duplicate check, AMQP-publish
- [x] `SoapHelper.java` – SOAP-parser + ACK/Pong/Error factory-methoden
- [x] `EbmsMessageProvider.java` – JAX-WS Provider<SOAPMessage> CXF endpoint
- [x] `PingEchoService.java` – Ping/Pong (ISO 15000-2)
- [x] `CxfEndpointConfig.java` – CXF bus-registratie

#### crypto-service – XML-DSig (RSA-SHA256 / ECDSA-SHA256)
- [x] JPA-entities: KeyPairMetadataEntity, CryptoAuditLogEntity
- [x] Repositories: KeyPairMetadataRepository, CryptoAuditLogRepository
- [x] `KeyStoreService.java` – PKCS12 KeyStore management (Bouncy Castle)
- [x] `XmlSigningService.java` – XML-DSig sign + verify + C14N (Apache Santuario)
- [x] `CryptoController.java` – REST API voor sign/verify/keys
- [x] DTOs: SignRequest, SignResponse, VerifyRequest, VerifyResponse

---

## Geprioriteerde Backlog

### P0 – Fase 3: SOAP Endpoint uitbreiding + CPA-integratie
- [ ] CPA-validatie in OrchestratorService (HTTP-call naar cpa-service)
- [ ] OIN-validatie via cpa-service (partyId ↔ X-Forwarded-Client-OIN check)
- [ ] WSDL-generatie voor het SOAP-endpoint
- [ ] SOAP MTOM-attachment verwerking

### P1 – Fase 4: XML-Enc encryptie
- [ ] `XmlEncryptionService.java` – AES-256-GCM data-encryptie + RSA-OAEP sleutelinkapseling
- [ ] Decryptie via crypto-service REST API

### P1 – Fase 5: Volledige Reliable Messaging
- [ ] Retry-scheduler (configureerbaar per CPA-profiel)
- [ ] ACK-ontvangst verwerking (inkomende Acknowledgment berichten)
- [ ] Duplicate elimination sliding window optimalisatie

### P2 – Fase 6: auditor-service
- [ ] Aparte Spring Boot service voor append-only event opslag
- [ ] Verwerkt AuditEvent AMQP-berichten van orchestrator en crypto-service

### P3 – Fase 7: Productie-klaar
- [ ] Kubernetes manifesten (Deployment, Service, Ingress, NetworkPolicy)
- [ ] mTLS Ingress Proxy configuratie
- [ ] Prometheus/Grafana monitoring dashboards
- [ ] Integratie tests (Testcontainers + JUnit 5)

---

## Digikoppeling Profielen
| Profiel    | RM  | Sign | Enc |
|------------|-----|------|-----|
| osb-be     | Nee | Nee  | Nee |
| osb-rm     | Ja  | Nee  | Nee |
| osb-be-s   | Nee | Ja   | Nee |
| osb-rm-s   | Ja  | Ja   | Nee |
| osb-be-e   | Nee | Ja   | Ja  |
| osb-rm-e   | Ja  | Ja   | Ja  |

## Referenties
- [Digikoppeling Koppelvlakstandaard ebMS2 v3.3.2](https://www.logius.nl/diensten/digikoppeling)
- [OASIS ebXML Messaging Services v2.0](https://www.oasis-open.org/standards#ebxmlmsg)
- [Apache CXF 4.x](https://cxf.apache.org/)
- [Apache Santuario](https://santuario.apache.org/)
