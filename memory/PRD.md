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

### Fase 3 (voltooid – februari 2026)

#### XML-Enc Encryptie (crypto-service)
- [x] `XmlEncryptionService.java` – AES-256-GCM encryptie + RSA-OAEP sleutelinkapseling
- [x] `EncryptRequest.java` / `EncryptResponse.java` – request/response DTOs
- [x] `DecryptRequest.java` / `DecryptResponse.java` – request/response DTOs
- [x] `CryptoController` uitgebreid: `POST /api/crypto/encrypt` + `POST /api/crypto/decrypt`

#### CPA-Validatie in Orchestrator
- [x] `CpaValidationService.java` – HTTP-aanroep naar cpa-service (CPA status + OIN check)
- [x] `CpaValidationResult.java` – resultaat-klasse (success/failure/serviceUnavailable)
- [x] `RestClientConfig.java` – Spring RestClient geconfigureerd voor cpa-service
- [x] `OrchestratorService` bijgewerkt: CPA-validatie vóór berichtverwerking (fail-closed)

#### Reliable Messaging Retry-Scheduler
- [x] `RetryProperties.java` – configuratie-properties (max-retries, retry-interval-seconds, etc.)
- [x] `OrchestratorService.retryFailedMessages()` – `@Scheduled` retry-scheduler
- [x] `application.yml` bijgewerkt: `retry-check-interval-ms: 300000`

#### Testcontainers Integratietests (uitgebreid – februari 2026)
- [x] `InboundPipelineIntegrationTest.java` – 9 testscenario's inbound pipeline (zie Fase 5)
- [x] `OutboundPipelineIntegrationTest.java` – 10 testscenario's outbound pipeline:
  - osb-be (Best Effort): geen crypto, status DELIVERED
  - osb-rm (Reliable Messaging): geen crypto, status SENT, isAckRequested=true
  - osb-be-s (signing only): sign aangeroepen, DELIVERED
  - osb-rm-s (signing + RM): sign aangeroepen, SENT
  - osb-rm-e (sign + encrypt): InOrder sign→encrypt, verstuurde XML='ENCRYPTED:SIGNED:...', SENT
  - Foutpad: null header → nack(requeue=false) → DLQ, geen DB-opslag
  - Retry: EbmsException eerste poging → requeue → tweede poging DELIVERED (upsert-idempotentie)
  - Audit-event: MESSAGE_SENT op ebms.audit.events
  - Payload-metadata: payloadRef/payloadContentType correct gepersisteerd
  - Throughput: 5 opeenvolgende berichten allemaal DELIVERED
- [x] Awaitility toegevoegd aan ebms-orchestrator pom.xml (scope=test, versie via Spring Boot BOM)

#### Bug-fix (februari 2026)
- [x] `OutboundMessageService.persistOutboundMessage()` – idempotente upsert toegevoegd:
  `findByMessageId().map(UPDATE).orElseGet(INSERT)` — lost unique-constraint fout op bij RabbitMQ retry (nack + requeue)

#### Code-kwaliteitsfix (februari 2026)
- [x] `CpaValidationService.java` – ontbrekende klasse-sluitaccolade `}` toegevoegd (EOF)
- [x] `OrchestratorService.java` – ontbrekende `import nl.logius.ebms.common.model.amqp.EbmsAckEvent` toegevoegd; FQN-gebruik vervangen door korte klassenaam
- [x] `cpa-service/application.yml` – `channel-by-party` toegevoegd aan `spring.cache.cache-names`
- [x] `ebms-orchestrator/application.yml` – `cache-names: [outbound-channel]` sectie toegevoegd
- [x] Alle 3 Dockerfiles – ENTRYPOINT in correcte exec-form (geen shell-variabele interpolatie)

#### Build-stabilisatie (februari 2026)
- [x] CXF upgrade: `4.0.4` → `4.1.8` – Spring Boot 3.4 / Spring 6.2 aligned; lost JAXB-ContextFactory hard-ref op + httpclient5 versieconflict
- [x] Santuario upgrade: `3.0.4` → `3.0.6` – bevat Java-21 module-toegang bug-fixes
- [x] Expliciete versie-pinning toegevoegd aan `dependencyManagement`: `httpclient5:5.4.1`, `woodstox-core:7.1.1`, `jakarta.activation-api:2.1.3`
- [x] `dependencyConvergence` enforcer-regel toegevoegd – faalt de build bij divergente transitive versies i.p.v. stille runtime crashes
- [x] `lombok-mapstruct-binding:0.2.0` toegevoegd als annotation-processor – garandeert correcte Lombok→MapStruct verwerkingsvolgorde op Java 21
- [x] Surefire `argLine` verbeterd: `@{argLine}` prefix (JaCoCo-compatibel) + extra `--add-opens` voor Santuario/Xerces/Bouncy Castle
- [x] `.mvn/jvm.config` aangemaakt – JVM-flags actief bij elke lokale `mvn`-aanroep
- [x] `.mvn/maven.config` aangemaakt – `--no-transfer-progress --batch-mode` voor alle builds
- [x] `.mvn/wrapper/maven-wrapper.properties` aangemaakt – pinned op Maven 3.9.9
- [x] Alle 3 Dockerfiles: `COPY .mvn .mvn` toegevoegd zodat config ook in Docker-build actief is


  - osb-be (plaintext): geen crypto, persistentie en AMQP-publish geverifieerd
  - osb-be-s (signed): `CryptoServiceClient.verify()` aangeroepen, decrypt NIET
  - osb-be-e (encrypted): `CryptoServiceClient.decrypt()` aangeroepen, ontsleutelde SOAP opgeslagen
  - osb-rm-e (encrypted + signed): decrypt → verify volgorde gegarandeerd via `InOrder`
  - Foutpad: ongeldige handtekening → `XmlSecurityException`, geen DB-opslag
  - Foutpad: dubbele messageId → `DuplicateMessageException`
  - Foutpad: CPA-validatie geblokkeerd → `EbmsException`, geen crypto-aanroepen
  - ACK-verwerking: SENT→DELIVERED status + `EbmsAckEvent` op `ebms.ack.events`
  - Audit: `AuditEvent` `MESSAGE_RECEIVED` gepubliceerd op `ebms.audit.events`



---

### Fase 4 (voltooid – februari 2026)

#### CryptoServiceClient – Zero-Trust HTTP-facade (ebms-orchestrator)
- [x] `CryptoServiceClient.java` – sign(), verify(), encrypt(), decrypt() via RestClient
- [x] `cryptoRestClient` @Bean toegevoegd aan `RestClientConfig`
- [x] HTTP 4xx/5xx vertaald naar `XmlSecurityException`
- [x] 4 crypto-DTO's verplaatst naar `ebms-common`: `SignResponse`, `VerifyResponse`, `EncryptResponse`, `DecryptResponse`

#### OutboundSoapClient – CXF Dispatch SOAP-client (ebms-orchestrator)
- [x] `OutboundSoapClient.java` – CXF `Dispatch<SOAPMessage>` Message mode, geen WSDL
- [x] Configureerbare timeouts via CXF `HTTPConduit` (`HTTPClientPolicy`)
- [x] Optionele mTLS `SSLContext` injectie (PKIoverheid-certificaten)
- [x] SOAP Fault detectie → `EbmsException`

#### OutboundMessageService – Asynchrone AMQP pipeline (ebms-orchestrator)
- [x] `OutboundMessageService.java` – `@RabbitListener(QUEUE_OUTBOUND)` met manual ack
- [x] `CpaChannelCacheService.java` – aparte `@Service` voor `@Cacheable` CPA-kanaal lookup (AOP proxy-fix)
- [x] Pipeline: CPA-lookup → signing → encryptie → verzenden → status-bijwerken
- [x] Status-machine: Best Effort → `DELIVERED`, Reliable Messaging → `SENT`
- [x] `RabbitMqConfig` uitgebreid met `SimpleRabbitListenerContainerFactory` (manual ack mode)
- [x] Caffeine cache geconfigureerd (`spring.cache.caffeine.spec`)

#### ACK-verwerking – Reliable Messaging completering (ebms-orchestrator)
- [x] `SoapHelper.isAcknowledgment()` + `parseRefToMessageId()` – ACK-detectie in SOAP header
- [x] `SoapHelper.buildOutboundSoap()` – SOAP-envelop opbouwen vanuit `EbxmlMessageHeader`
- [x] `OrchestratorService.handleAcknowledgment()` – status `SENT` → `DELIVERED` via `RefToMessageId`
- [x] `EbmsMessageProvider` – ACK-routering vóór normale berichtverwerking

#### cpa-service uitbreiding
- [x] `CpaDeliveryChannelEntity.java` + `CpaDeliveryChannelRepository.java`
- [x] `CpaService.findDeliveryChannel()`, `findDeliveryChannels()`, `addDeliveryChannel()`
- [x] `CpaController`: `GET /api/cpa/{cpaId}/channels`, `GET /api/cpa/{cpaId}/channels/{partyId}`, `POST /api/cpa/{cpaId}/channels`
- [x] `CpaMapper.toChannelDto()` + `toChannelDtoList()`

#### Flyway + DB
- [x] `V2__add_sent_status.sql` – PostgreSQL ENUM uitgebreid met `SENT`
- [x] `MessageStatus.SENT` toegevoegd aan Java enum

### Fase 5 – E2E Inbound Pipeline + ACK Notificatie + Helm (voltooid – februari 2026)

#### ebms-orchestrator – Inbound crypto pipeline
- [x] `OrchestratorService.processInboundMessage()` uitgebreid:
  - Stap 2: Inbound XML-Enc decryptie via `CryptoServiceClient.decrypt()` (enkel bij versleuteld bericht)
  - Stap 3: Inbound XML-DSig verificatie via `CryptoServiceClient.verify()` (enkel bij ondertekend bericht)
  - `processedSoap` string consistent doorgegeven aan verify, entity opslag en AMQP-publish
  - `@Value("${ebms.inbound.decryption-key-alias:encryption-key}")` voor configureerbaar decryptie-alias
- [x] `OrchestratorService.handleAcknowledgment()` uitgebreid:
  - Publiceert `EbmsAckEvent` op `ebms.ack.events` queue na SENT→DELIVERED status-update
  - `ackSenderPartyId` gevuld met `entity.getToPartyId()` (de partij die de ACK stuurde)
  - Try/catch zodat mislukte AMQP-publish de transactie niet blokkeert
- [x] `SoapHelper.java` uitgebreid:
  - `hasSignature()` – detecteert XML-DSig `ds:Signature` in header én body
  - `hasEncryptedBody()` – detecteert XML-Enc `xenc:EncryptedData` in body
  - `isAcknowledgment()` – detecteert ebMS2 `Acknowledgment` in SOAP-header
  - `parseRefToMessageId()` – parseert `RefToMessageId` uit inkomende ACK
  - `buildOutboundSoap()` – construeert volledige SOAP 1.1 envelop met ebXML MessageHeader
  - `SOAP-ENV:actor` namespace correct op `AckRequested` element (SOAP 1.1 conformant)

#### ebms-common – Nieuw AMQP event
- [x] `EbmsAckEvent.java` – backoffice notificatie-event voor definitieve bevestiging rm-berichten

#### Helm umbrella chart (`/app/ebms-adapter/helm/`)
- [x] `Chart.yaml` – umbrella chart met bitnami/postgresql en bitnami/rabbitmq dependencies
- [x] `values.yaml` – globale values voor alle subcharts (Kong Ingress, resources, autoscaling, secrets)
- [x] Subcharts: `ebms-orchestrator`, `cpa-service`, `crypto-service`
  - Deployment, Service, ConfigMap, Secret, HPA, ServiceAccount templates
  - `ebms-orchestrator` ingress: Kong Ingress Controller annotaties (protocols, strip-path, plugins)
  - `KongPlugin` rate-limiting resource (100 req/min) binnen `ingress.enabled` guard
  - `crypto-service`: PVC voor PKIoverheid keystore
- [x] `postgresql-initdb-configmap.yaml` – aanmaken van 3 databases bij PostgreSQL initialisatie

---


### Release 1.0 MVP – Go-Live scope (voltooid – augustus 2026)

Scope-akkoord met gebruiker: alleen Task 1 (outbound mTLS) + Task 2 (message monitoring UI).
Reliable Messaging Acks, keystore-rotatie en zware audit-logging expliciet uitgesteld.

#### Task 1 – Outbound mTLS Client (ebms-orchestrator)
- [x] `soap/EbmsOutboundSSLProperties.java` – `@ConfigurationProperties(prefix="ebms.outbound.ssl")`:
  keystore-path, keystore-password, truststore-path, truststore-password (env: KEYSTORE_PATH,
  KEYSTORE_PASSWORD, TRUSTSTORE_PATH, TRUSTSTORE_PASSWORD)
- [x] `soap/OutboundSoapClient.java` – constructor injecteert `EbmsOutboundSSLProperties` i.p.v.
  losse `SSLContext`-bean; bouwt SSLContext via Apache HttpClient 5
  `org.apache.hc.core5.ssl.SSLContexts.custom().loadKeyMaterial()/.loadTrustMaterial()`
  - Graceful fallback: ontbrekende/lege keystore-config → warning-log + plain HTTP/HTTPS
  - Bestaande CXF `HTTPConduit`/`TLSClientParameters`-injectie in `configureMtls()` ongewijzigd
- [x] `pom.xml` – `httpclient5` dependency toegevoegd (versie via parent-BOM 5.4.1, geen conflict)
- [x] `application.yml` – `ebms.outbound.ssl.*` block met env-var placeholders

#### Task 2 – Basic Message Monitoring REST API + Admin UI
- [x] `controller/MessageController.java` – `GET /api/admin/messages?page=&size=` (default size 50,
  sort timestamp DESC), retourneert `Page<MessageDto>` via `EbmsMessageRepository.findAll(Pageable)`
- [x] `dto/MessageDto.java` – read-only record (geen raw JPA-entity serialisatie)
- [x] `static/admin/index.html` – vanilla JS + Tailwind CDN dashboard, bereikbaar op
  `/admin/index.html`; tabel laatste 50 berichten, statuskleuren (groen/amber/rood/blauw),
  details-modal met volledige payload-metadata + raw SOAP XML, auto-refresh 10s, paginering

**Let op:** kon niet lokaal compileren/testen — deze sandbox heeft geen JDK/Maven geïnstalleerd
(alleen bedoeld voor het voorbereiden van bestanden; build/deploy gebeurt via `deploy.sh` door de
gebruiker). Code is grondig handmatig gereviewd op imports, method-signatures en package-conventies
conform bestaande stijl (RestClientConfig, RetryProperties patterns).

#### Task 2 uitbreiding – CPA Beheer tab (voltooid – augustus 2026)
- [x] `static/admin/index.html` – tab-navigatie toegevoegd (Berichten / CPA Beheer), single file
- [x] Drag-and-drop CPA XML upload-zone + bestaande-CPA zoekfunctie (`GET /api/cpa/{cpaId}`)
- [x] Browser-side `DOMParser` parsing (namespace-agnostisch: `getElementsByTagNameNS('*', ...)`)
  van cpaid, Status, Start/End, PartyInfo (naam + PartyId/OIN + Endpoint + certificaatdetectie)
- [x] Visuele netwerk-kaart: partij-kaarten met OIN, endpoint-badge en certificaat-badge,
  verbonden met een "ebMS 2.0"-lijn
- [x] JS-syntax gevalideerd via `node --check`; HTML-tags balans gecontroleerd
- **Afwijking van opdracht (bewust, na code-inspectie):** `cpa-service`'s `POST /api/cpa` accepteert
  alléén JSON `CpaDto` (`cpaId`, `cpaXml`, `status`, `startDate`, `endDate`) — geen raw
  `Content-Type: application/xml`-body. De dashboard-JS parsed de XML client-side en verzendt het
  resultaat als JSON naar `/api/cpa`; dit is de enige manier waarop de upload met de bestaande
  backend werkt. Geen backend-wijzigingen nodig.
- **Bekende infra-aanname (niet opgelost, buiten scope):** de Helm-ingress van `ebms-orchestrator`
  routeert alleen `/soap/ebms`; er is geen Kong-route naar `cpa-service` op `/api/cpa` in het huidige
  chart. Als de orchestrator en cpa-service los van elkaar draaien zonder gedeelde ingress-route,
  moet de gebruiker zelf `/api/cpa` proxyen naar `cpa-service:8081`, of de dashboard-fetch-URL
  aanpassen naar een absolute cpa-service-URL.

#### Kong CPA-route toegevoegd (opgelost – augustus 2026)
- [x] `helm/charts/ebms-orchestrator/templates/ingress.yaml` – extra path-regel per host:
  `/api/cpa` (Prefix) → backend-service `cpa-service` i.p.v. de orchestrator-service, binnen
  dezelfde Ingress (zelfde origin als het admin-dashboard, dus geen CORS nodig)
  - `strip-path: "false"` (bestaande annotatie) behoudt het volledige pad, wat exact aansluit op
    `CpaController`'s `@RequestMapping("/api/cpa")` — geen path-rewrite nodig
- [x] `helm/values.yaml` – nieuw blok `ebms-orchestrator.ingress.cpaServiceRoute`:
  `enabled`, `path: /api/cpa`, `pathType: Prefix`, `serviceName` (leeg = auto `<release>-cpa-service`),
  `servicePort: 8081`
- **Let op (pre-existing, niet gewijzigd):** `CPA_SERVICE_URL` default in values.yaml is
  `http://cpa-service:8081`, maar cpa-service's Helm-fullname zonder `nameOverride` is
  `<release-name>-cpa-service`. Dit is een bestaande naamgevings-mismatch los van deze taak; de
  nieuwe ingress-route rekent daarom met `<release-name>-cpa-service` (override via
  `cpaServiceRoute.serviceName` indien de release-naam afwijkt).
- Kon niet valideren met `helm template` (geen helm/kubectl in deze sandbox) — YAML-indentatie en
  Go-template `if`/`range`/`end`-balans handmatig gecontroleerd.

#### Bugfixes na live-test in HAVEN k3s v8 (opgelost – augustus 2026)
- [x] `static/admin/index.html` – `partyName` werd niet gevonden omdat het een ATTRIBUUT is op
  `<tp:PartyInfo>` (bv. `tp:partyName="..."`), geen child-element. Fix: leest nu eerst
  `getAttribute("tp:partyName")`, dan `"partyName"`, dan `"PartyName"` (fallback-keten behouden)
- [x] `controller/MessageController.java` + `repository/EbmsMessageRepository.java` – bestaande
  `findAll(pageable)` had géén richting-filter (dus toonde al INBOUND+OUTBOUND); expliciet gemaakt
  door een optionele `?direction=INBOUND|OUTBOUND` query-param toe te voegen
  (`EbmsMessageRepository.findByDirection`) terwijl het endpoint zonder param nog steeds ALLE
  berichten teruggeeft — verwijdert elke ambiguïteit over de "alle berichten"-garantie
- JS-syntax herverifieerd via `node --check` na de partyName-fix

### v9-deployment fixes (HAVEN k3s) – augustus 2026
- [x] `XmlSigningService.sign()` (crypto-service) – SOAP-aware handtekening-parent: zoekt eerst
  `soapenv:Header`/`SOAP-ENV:Header` (SOAP 1.1 + 1.2 namespaces + prefix-fallbacks) en plaatst
  `<ds:Signature>` daar i.p.v. direct onder `soapenv:Envelope` (root). Root blijft fallback voor
  niet-SOAP XML. Lost schema-violation op waardoor SAAJ/CXF de handtekening bij ontvangst
  stilletjes verwierp.
- [x] `helm/values.yaml` – `/admin` en `/api/admin` toegevoegd aan
  `ebms-orchestrator.ingress.hosts[0].paths` (admin-dashboard + REST-API bereikbaar via Kong)
- [x] `deployment-guide-haven-openshift.md` (NIEUW, project-root) – keystore Secret-volume
  correctie (i.p.v. lege PVC), Kong route-tabel (`/soap/ebms`, `/admin`, `/api/admin`, `/api/cpa`),
  E2E-validatiestappen voor outbound signing + inbound verificatie + mTLS
- **Niet opgelost (bewust, buiten scope – alleen gedocumenteerd):** de Helm-charts van
  `crypto-service`/`ebms-orchestrator` mounten nog steeds de lege PVC i.p.v. een Secret-volume voor
  de keystore. Zie backlog "Keystore Secret-volume Helm-fix".
- Kon niet compileren (geen JDK/Maven in sandbox) — testing_agent ingeschakeld voor verificatie.
- **Testing_agent verificatie (geslaagd):** JDK 21 + Maven 3.9.9 gedownload in sandbox; nieuwe
  `crypto-service/src/test/java/nl/logius/ebms/crypto/service/XmlSigningServiceTest.java` (5 JUnit 5
  tests: SOAP 1.1, SOAP 1.2, literal-tag fallback, non-SOAP root-fallback regressie, anti-regressie
  "Signature nooit direct onder Envelope"). `mvn test` → BUILD SUCCESS, 5/5 groen. Bevestigd:
  `Signature.parentNode` = SOAP Header (niet Envelope) voor SOAP-input; root-fallback ongewijzigd
  voor niet-SOAP XML. Geen `retest_needed`.
### Fix: rauwe XML-capture voor XML-DSig verificatie (HAVEN v9 – augustus 2026)
- **Root cause (bevestigd via crypto-service verify-logs, valid=false):** `EbmsMessageProvider`
  bouwde `rawSoap` via `soapHelper.soapToString(request)` = `SOAPMessage.writeTo()` op het reeds
  SAAJ-geparseerde object — een herserialisatie die de infoset kan wijzigen (whitespace/MIME-framing),
  wat de C14N-gebaseerde digest bij XML-DSig verificatie liet mismatchen.
- [x] `soap/RawPayloadCaptureInterceptor.java` (NIEUW) – CXF `AbstractPhaseInterceptor<Message>` op
  `Phase.RECEIVE`; cachet de rauwe HTTP-body via `CachedOutputStream` vóór SAAJ-parsing, zet 'm op
  `Exchange` (`RAW_XML_PAYLOAD`), en herstelt een verse leesbare `InputStream` voor de SOAP-parser.
  Drie onafhankelijke try/catch-stages (cache/extract/reset) loggen WARN i.p.v. te falen (Fault) —
  degradeert netjes naar de `soapToString()`-fallback. Charset volgt `Message.ENCODING`, met
  UTF-8 fallback.
- [x] `config/CxfEndpointConfig.java` – `endpoint.getInInterceptors().add(new RawPayloadCaptureInterceptor())`
  vóór `publish("/ebms")`.
- [x] `soap/EbmsMessageProvider.java` – nieuwe `extractRawXmlPayload()` leest
  `wsContext.getMessageContext().get(RAW_XML_PAYLOAD)` i.p.v. rechtstreeks `soapToString()`; fallback
  behouden. Ping/ACK-paden ongewijzigd (roepen deze methode niet aan).
- **Testing_agent verificatie (geslaagd, 2 runs):** iteratie 11 – 4 nieuwe JUnit 5 tests, bytecode-
  geverifieerd dat CXF 4.1.8's `WrappedMessageContext.get()` doorschakelt naar `Exchange.get()`
  (Exchange→WebServiceContext-propagatie bevestigd, niet enkel aangenomen). Iteratie 12 (na hardening-
  refactor) – 5/5 tests groen, incl. nieuwe IOException-degradatietest. Geen `retest_needed`.

### P0 – Fase 4: auditor-service (NIEUW)
- [ ] Aparte Spring Boot microservice op poort 8083 voor append-only audit-events
- [ ] Verwerkt `AuditEvent` AMQP-berichten van orchestrator en crypto-service
- [ ] PostgreSQL-schema met `audit_event` tabel
- [ ] REST API voor querying van audit-events

### P1 – Fase 5: Ingress Proxy + mTLS
- [ ] Nginx/HAProxy als mTLS-offloader
- [ ] OIN-header injectie (X-Forwarded-Client-OIN) vanuit client-certificaat
- [ ] Kubernetes NetworkPolicy-configuratie

### P2 – Fase 6: Productie-klaar
- [ ] Kubernetes manifesten (Deployment, Service, Ingress, NetworkPolicy)
- [ ] Prometheus/Grafana monitoring dashboards
- [ ] WSDL-generatie voor het SOAP-endpoint
- [ ] SOAP MTOM-attachment verwerking
- [ ] ACK-ontvangst verwerking (inkomende Acknowledgment berichten)

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
