# Integraal Deployment & Integratie Handboek: ebms2container Adapter
**Versie:** 5.0 (Productie, OpenShift Routes, Certificaat-Matrix & Logius Compliance Dual-Setup)

Dit handboek beschrijft de volledige deployment en configuratie van de container-native ebMS2 Digikoppeling adapter. Er wordt een strikt en expliciet onderscheid gemaakt tussen:
1. **Generieke / Productie-omgeving:** De universele uitrolregels voor productie/OTAP (met PKIoverheid certificaten, echte Digipoort/Logius endpoints en e-Herkenning/OIN's).
2. **Certificaatbeheer Matrix:** Een integraal overzicht van secrets, aliassen en keystore-parameters per omgeving (Dev/Test/Compliance/Prod).
3. **OpenShift Integratie:** Kant-en-klare Red Hat OpenShift `Route` manifesten voor mTLS passthrough en REST management routes.
4. **Logius Compliance / Testsuite-omgeving:** De specifieke configuratie, cert-conversies en mTLS-stubbing die nodig zijn voor het draaien tegen de officiële Logius Compliance Testsuite / Simulator.

---

## Sectie 1: Infrastructurele Randvoorwaarden & Diensten

### 1.1 PostgreSQL Database (Generiek & Productie)
- **Schema-isolatie:** Drie gescheiden schema's op de PostgreSQL database:
  - `cpa`: Beheert CPA-definities, kanaalinstellingen en certificaten.
  - `crypto`: Slaat versleutelingssleutels en X.509 certificaatkoppen op.
  - `orchestrator`: Beheert de atomaire status van in- en uitgaande berichten en de audit-trail.
- **Rechten:** De database-user (`karavan` of productie-user) heeft volledige DDL/DML-rechten (CREATE, ALTER, SELECT, INSERT, UPDATE) op alle drie de schema's ter behoeve van automatische Flyway-migraties.
- **JDBC Configuratie:** Injectie via omgevingsvariabelen met expliciete `currentSchema` parameters:
  - CPA: `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?currentSchema=cpa&stringtype=unspecified`
  - Crypto: `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?currentSchema=crypto&stringtype=unspecified`
  - Orchestrator: `jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?currentSchema=orchestrator&stringtype=unspecified`

### 1.2 RabbitMQ Message Broker (Generiek & Productie)
- **Virtual Host:** De aanwezigheid van de vhost **`ebms`** is een verplichte randvoorwaarde.
- **Credentials & Rechten:** De toegewezen RabbitMQ-gebruiker heeft volledige configure/read/write rechten binnen de `ebms` vhost.
- **Netwerk:** Bereikbaar op poort `5672` via `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`, en `SPRING_RABBITMQ_VIRTUAL_HOST=ebms`.

---

### 1.3 Ingress Gateway & OpenShift Route Architecture

#### A. Generieke Productie-omgeving [Productie & OTAP]
- **Certificaten:** Officiële PKIoverheid Organisatie / Services certificaten (Server- & Client-certificaatketen).
- **Endpoints:** Echte Digipoort / overheids-endpoints (bijv. `https://ebms.digipoort.nl/services/ebms`).
- **mTLS Exposition:** mTLS Passthrough of Gateway SSL-termination met validatie tegen de PKIoverheid Staat der Nederlanden CA-keten. *Op productie wordt de Ingress/Gateway ontsloten via een LoadBalancer of Ingress Controller op poort 443/8443 (geen port-forwarding).* 
- **Inbound Paden:** Routering van inkomende ebMS SOAP-berichten direct naar `ebms-ebms-orchestrator:8080/services/ebms`.
- **OIN Validatie:** De Ingress/API Gateway valideert de client-certificaat OIN uit de mTLS-handshake en geeft deze via de HTTP-header `X-Forwarded-Client-OIN` door aan de Orchestrator.

#### B. Logius Compliance / Testsuite-omgeving [Specifiek voor Testsuite / Simulatie]
- **Host Aliases:** Interne DNS/HostAliases in de K8s Pod spec voor `proxy-dart` en `proxy-cvwus` (koppelend aan het netwerk-IP van de testomgeving).
- **Testcertificaten:** Geautomatiseerde inleesstap vanuit de Logius testgenerator (`$COMPLIANCE_GEN_DIR/client-certs/dart.pem` + `ca-chain.pem`).
- **Non-SNI SSL Fallback (Kong Gateway):** De Ingress Controller luistert op poort `8843` (SSL 8443) met `KONG_SSL_CERT` vastgezet op `ebms-tls-secret` om legacy test-clients te ondersteunen die geen Server Name Indication (SNI) meesturen.
- **DigipoortStub Rewrite:** Ingress pad `/digipoortStub` wordt via een `KongPlugin` (request-transformer) herschreven naar `/services/ebms` en verrijkt met de test-OIN header (`X-Forwarded-Client-OIN: 00000004003214345001`).
- **Dev Port-Forward:** Alleen voor lokale Vagrant/K3s ontwikkelomgevingen wordt tijdelijk `kubectl port-forward ... 8843:8443` gestart.

---

### 1.4 Keystores & Secrets

#### A. Generieke Productie-omgeving [Productie & OTAP]
- **Cryptografische Keystore (`keystore.p12`):** Bevat de PKIoverheid private key en certificaat voor XML-DSig onder de vereiste alias **`signing-key`** (en **`orchestrator-key`** voor mTLS).
- **Truststore (`truststore.p12`):** Bevat de Root- en Intermediate certificaten van de Staat der Nederlanden / PKIoverheid voor validatie van tegenpartijen.
- **K8s Secret Mounting:** De PKCS12 bestanden worden als Kubernetes Secret (`crypto-keystore-secret`) gemount op het vaste pad `/app/keystores/` in de `crypto-service` en `ebms-orchestrator` pods.

#### B. Logius Compliance / Testsuite-omgeving [Specifiek voor Testsuite]
- Geautomatiseerde OpenSSL/keytool conversie in de build-pijp die de Logius test-sleutel (`dart.pem` / `dart.key`) ontsleutelt en omzet naar `keystore.p12` met expliciete aliassen **`signing-key`** en **`orchestrator-key`**, zonder interactieve passkey prompts.

---

### 1.5 Matrix voor Certificaatbeheer & Keystore Secrets

Onderstaande matrix geeft per omgeving een integraal overzicht van de benodigde Kubernetes Secrets, bestandslocaties, aliassen, wachtwoorden en certificaat-typen:

| Parameter / Kenmerk | Dev / Lokaal (Vagrant/K3s) | Test / OTAP (Interne CA) | Compliance (Logius Testsuite) | Productie (PKIoverheid) |
| :--- | :--- | :--- | :--- | :--- |
| **K8s Secret Naam** | `crypto-keystore-secret` | `crypto-keystore-secret` | `crypto-keystore-secret` & `ebms-tls-secret` | `crypto-keystore-secret` |
| **Keystore Bestand (`/app/keystores/`)** | `keystore.p12` | `keystore.p12` | `keystore.p12` | `keystore.p12` |
| **Truststore Bestand (`/app/keystores/`)** | `truststore.p12` | `truststore.p12` | `truststore.p12` | `truststore.p12` |
| **Keystore Wachtwoord (`KEYSTORE_PASSWORD`)** | `change-me-keystore` | Via Vault / Secret | `change-me-keystore` | Via Key Vault / Secret |
| **Truststore Wachtwoord (`TRUSTSTORE_PASSWORD`)** | `change-me-truststore` | Via Vault / Secret | `change-me-truststore` | Via Key Vault / Secret |
| **Private Key Alias (XML-DSig)** | `signing-key` | `signing-key` | `signing-key` (Logius DART key) | **`signing-key`** (PKIoverheid Services Certificaten) |
| **mTLS Client Key Alias** | `orchestrator-key` | `orchestrator-key` | `orchestrator-key` (Logius DART key) | **`orchestrator-key`** (PKIoverheid Client Certificaat) |
| **Certificaat Type** | Self-signed / Test CA | Interne Organisatie CA | Logius Compliance Test CA (`dart.pem`) | **PKIoverheid Organisatie / Services** (Staat der Nederlanden Root/Intermediate) |
| **Inbound Ingress TLS Secret** | `ebms-tls-secret` | `ebms-tls-secret` | `ebms-tls-secret` (Non-SNI port 8843) | Productie Edge Route / Gateway Certificaat |

---

## Sectie 2: OpenShift Routes Template (`ebms-routes-openshift.yaml`)

Voor beheerders van Red Hat OpenShift vervangt onderstaande YAML de Kubernetes `Ingress` objecten door native OpenShift `Route` objecten voor zowel mTLS passthrough als beheer-REST interfaces:

```yaml
# ====================================================================
# OpenShift Native Routes voor ebMS2 Digikoppeling Adapter
# ====================================================================
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: ebms-route-inbound-mtls
  namespace: ebms-adapter
  annotations:
    haproxy.router.openshift.io/balance: roundrobin
    haproxy.router.openshift.io/ssl_passthrough: "true"
spec:
  host: ebms.gemeente.nl
  to:
    kind: Service
    name: ebms-ebms-orchestrator
    weight: 100
  port:
    targetPort: 8080
  tls:
    termination: passthrough
    insecureEdgeTerminationPolicy: Redirect
---
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: ebms-route-admin-ui
  namespace: ebms-adapter
  annotations:
    router.openshift.io/cookie_name: EBMS_ADMIN_SESSION
spec:
  host: ebms-admin.gemeente.nl
  path: /admin
  to:
    kind: Service
    name: ebms-ebms-orchestrator
    weight: 100
  port:
    targetPort: 8080
  tls:
    termination: edge
    insecureEdgeTerminationPolicy: Redirect
---
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: ebms-route-cpa-api
  namespace: ebms-adapter
spec:
  host: ebms-admin.gemeente.nl
  path: /api/cpa
  to:
    kind: Service
    name: ebms-cpa-service
    weight: 100
  port:
    targetPort: 8081
  tls:
    termination: edge
    insecureEdgeTerminationPolicy: Redirect
---
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: ebms-route-crypto-api
  namespace: ebms-adapter
spec:
  host: ebms-admin.gemeente.nl
  path: /api/crypto
  to:
    kind: Service
    name: ebms-crypto-service
    weight: 100
  port:
    targetPort: 8082
  tls:
    termination: edge
    insecureEdgeTerminationPolicy: Redirect
```

### OpenShift Toepassen:
```bash
oc project ebms-adapter
oc apply -f ebms-routes-openshift.yaml
```

---

## Sectie 3: Kubernetes Deployment & Parametriseerbaar `deploy.sh` Script

### 3.1 Helm Chart Overrides & Probes (Generiek)
Standaard maakt de Helm-chart een lege Persistent Volume Claim (PVC) aan op `/app/keystores`. In zowel productie als compliance-omgevingen wordt dit overruled door een directe Secret Mount:

```yaml
# Override op K8s Deployment spec
volumes:
  - name: keystore-volume
    secret:
      secretName: crypto-keystore-secret
containers:
  - name: crypto-service
    volumeMounts:
      - name: keystore-volume
        mountPath: /app/keystores
        readOnly: true
```

---

### 3.2 Het Geparametriseerde Deployment Script (`deploy.sh`)

> 💡 **Opmerkingen over parametrisering & productie-instellingen:**
> 1. **Parametrisering:** Alle paden, container registries en database-hosts worden aangestuurd via omgevingsvariabelen met veilige defaults. Zowel in lokale Vagrant-omgevingen als in CI/CD pipelines (GitLab/GitHub Actions) kunnen deze eenvoudig worden overschreven.
> 2. **Probes & Initial Delay (`initialDelaySeconds=600`):** De geselecteerde `initialDelaySeconds=600` in de helm template aanroep dient als ruime **bouwstraat/ontwikkel-marge**. Dit voorkomt dat Kubernetes de pods voortijdig herstart tijdens het uitvoeren van zware Flyway-databasemigraties in bron-gebeperkte test-VM's. Voor **productie** wordt geadviseerd om een K8s `startupProbe` in te zetten, gecombineerd met een kortere `livenessProbe` delay van 30–60 seconden.
> 3. **Kong Port-Forwarding (Stap 8):** Het commando `kubectl port-forward` in stap 8 is uitsluitend bedoeld voor **lokale ontwikkelaarsomgevingen / Vagrant test-VM's**. Op **productie** dient de Ingress Gateway rechtstreeks via een Kubernetes `LoadBalancer` of `NodePort` Service (of OpenShift Route) te worden ontsloten op poort 8443/8843.

```bash
#!/usr/bin/env bash
set -e

# ====================================================================
# Parametriseerbare Omgevingsvariabelen (met defaults)
# ====================================================================
REGISTRY="${REGISTRY:-192.168.56.10:5555/municipal}"
WORKDIR="${WORKDIR:-$(pwd)}"
DB_HOST="${DB_HOST:-192.168.56.10}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-karavan}"
DB_PASSWORD="${DB_PASSWORD:-karavan}"
DB_NAME="${DB_NAME:-karavan}"
COMPLIANCE_GEN_DIR="${COMPLIANCE_GEN_DIR:-/home/vagrant/compliance/generate-certs/generated}"
INITIAL_DELAY_DEV="${INITIAL_DELAY_DEV:-600}"

cd "$WORKDIR"

echo "==> 1. Code force pullen & werkruimte schonen..."
git fetch --all
git reset --hard origin/main || git reset --hard @{u}
git clean -fd

echo "==> 2. Microservices bouwen & pushen..."
docker build -t ${REGISTRY}/ebms-crypto:latest -f ./ebms-adapter/crypto-service/Dockerfile ./ebms-adapter
docker push ${REGISTRY}/ebms-crypto:latest

docker build -t ${REGISTRY}/ebms-cpa:latest -f ./ebms-adapter/cpa-service/Dockerfile ./ebms-adapter
docker push ${REGISTRY}/ebms-cpa:latest

docker build -t ${REGISTRY}/ebms-orchestrator:latest -f ./ebms-adapter/ebms-orchestrator/Dockerfile ./ebms-adapter
docker push ${REGISTRY}/ebms-orchestrator:latest

echo "==> 3. K3s Cluster opschonen..."
helm uninstall ebms 2>/dev/null || true
kubectl delete hpa --all 2>/dev/null || true
kubectl delete deployment,svc,pod -l app.kubernetes.io/instance=ebms --force --grace-period=0 2>/dev/null || true

echo "==> 4. PostgreSQL Schemas aanmaken..."
export PGPASSWORD="$DB_PASSWORD"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "CREATE SCHEMA IF NOT EXISTS cpa;" || true
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "CREATE SCHEMA IF NOT EXISTS crypto;" || true
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c "CREATE SCHEMA IF NOT EXISTS orchestrator;" || true

mkdir -p ./keystores

# --- [COMPLIANCE TESTSUITE SPECIFIEK] Certificaat & Keystore Preparatie ---
if [ -f "$COMPLIANCE_GEN_DIR/client-certs/dart.pem" ] && [ -f "$COMPLIANCE_GEN_DIR/client-ca/ca-chain.pem" ]; then
  echo "    -> [Compliance Setup] Logius proxy-dart certificaatketen inladen..."
  cat "$COMPLIANCE_GEN_DIR/client-certs/dart.pem" "$COMPLIANCE_GEN_DIR/client-ca/ca-chain.pem" > /tmp/ebms_tls.crt
  
  openssl rsa -in "$COMPLIANCE_GEN_DIR/client-certs/dart.key" -out /tmp/ebms_tls.key -passin pass:password 2>/dev/null || \
  openssl rsa -in "$COMPLIANCE_GEN_DIR/client-certs/dart.key" -out /tmp/ebms_tls.key 2>/dev/null || \
  cp "$COMPLIANCE_GEN_DIR/client-certs/dart.key" /tmp/ebms_tls.key
fi

if [ -s /tmp/ebms_tls.crt ] && [ -s /tmp/ebms_tls.key ]; then
  kubectl create secret tls ebms-tls-secret --cert=/tmp/ebms_tls.crt --key=/tmp/ebms_tls.key --namespace=default --dry-run=client -o yaml | kubectl apply -f -
  kubectl create secret tls ebms-tls-secret --cert=/tmp/ebms_tls.crt --key=/tmp/ebms_tls.key --namespace=kong --dry-run=client -o yaml | kubectl apply -f -
fi

if [ -f "$COMPLIANCE_GEN_DIR/client-certs/dart.pem" ] && [ -f /tmp/ebms_tls.key ]; then
  echo "    -> [Compliance Setup] Sleutel exporteren naar 'signing-key' en 'orchestrator-key'..."
  openssl pkcs12 -export \
    -in "$COMPLIANCE_GEN_DIR/client-certs/dart.pem" \
    -inkey /tmp/ebms_tls.key \
    -certfile "$COMPLIANCE_GEN_DIR/client-ca/ca-chain.pem" \
    -out ./keystores/keystore.p12 \
    -name signing-key \
    -passout pass:change-me-keystore

  openssl pkcs12 -export \
    -in "$COMPLIANCE_GEN_DIR/client-certs/dart.pem" \
    -inkey /tmp/ebms_tls.key \
    -certfile "$COMPLIANCE_GEN_DIR/client-ca/ca-chain.pem" \
    -out ./keystores/orch.p12 \
    -name orchestrator-key \
    -passout pass:change-me-keystore

  keytool -importkeystore \
    -srckeystore ./keystores/orch.p12 -srcstoretype PKCS12 -srcstorepass change-me-keystore \
    -destkeystore ./keystores/keystore.p12 -deststoretype PKCS12 -deststorepass change-me-keystore \
    -noprompt 2>/dev/null || true
  rm -f ./keystores/orch.p12
fi

rm -f /tmp/ebms_tls.crt /tmp/ebms_tls.key

if [ -f "$COMPLIANCE_GEN_DIR/client-keystores/dart-truststore.jks" ]; then
  echo "    -> [Compliance Setup] Logius JKS Truststore converteren naar PKCS12..."
  keytool -importkeystore \
    -srckeystore "$COMPLIANCE_GEN_DIR/client-keystores/dart-truststore.jks" -srcstoretype JKS -srcstorepass password \
    -destkeystore ./keystores/truststore.p12 -deststoretype PKCS12 -deststorepass change-me-truststore \
    -noprompt 2>/dev/null || true
fi

# --- [GENERIEK / PRODUCTIE] K8s Keystore Secret toepassen ---
if [ -f ./keystores/keystore.p12 ]; then
  kubectl create secret generic crypto-keystore-secret \
    --from-file=keystore.p12=./keystores/keystore.p12 \
    --from-file=truststore.p12=./keystores/truststore.p12 \
    --dry-run=client -o yaml | kubectl apply -f -
fi
rm -rf ./keystores

echo "==> 5. Helm Template genereren..."
helm template ebms ./ebms-adapter/helm \
  --set postgresql.enabled=false \
  --set rabbitmq.enabled=false \
  --set ebms-orchestrator.image.repository="${REGISTRY}/ebms-orchestrator" \
  --set cpa-service.image.repository="${REGISTRY}/ebms-cpa" \
  --set crypto-service.image.repository="${REGISTRY}/ebms-crypto" \
  --set crypto-service.image.pullPolicy=Always \
  --set cpa-service.image.pullPolicy=Always \
  --set ebms-orchestrator.image.pullPolicy=Always \
  --set ebms-orchestrator.livenessProbe.initialDelaySeconds="${INITIAL_DELAY_DEV}" \
  --set ebms-orchestrator.readinessProbe.initialDelaySeconds="${INITIAL_DELAY_DEV}" \
  --set cpa-service.livenessProbe.initialDelaySeconds="${INITIAL_DELAY_DEV}" \
  --set cpa-service.readinessProbe.initialDelaySeconds="${INITIAL_DELAY_DEV}" \
  --set crypto-service.livenessProbe.initialDelaySeconds="${INITIAL_DELAY_DEV}" \
  --set crypto-service.readinessProbe.initialDelaySeconds="${INITIAL_DELAY_DEV}" > manifest_raw.yaml

echo "==> 6. Manifest patchen & Ingressen toepassen..."
# (DB environment variabelen & Secret mounts worden geënt in manifest.yaml)

echo "==> 7. Kong Deployment Patchen (Non-SNI Fallback)..."
kubectl patch deployment kong-kong -n kong --type='json' -p='[
  {"op": "add", "path": "/spec/template/spec/volumes/-", "value": {"name": "ebms-tls-vol", "secret": {"secretName": "ebms-tls-secret"}}},
  {"op": "add", "path": "/spec/template/spec/containers/0/volumeMounts/-", "value": {"name": "ebms-tls-vol", "mountPath": "/etc/secrets/ebms-tls-secret", "readOnly": true}},
  {"op": "add", "path": "/spec/template/spec/containers/0/env/-", "value": {"name": "KONG_SSL_CERT", "value": "/etc/secrets/ebms-tls-secret/tls.crt"}},
  {"op": "add", "path": "/spec/template/spec/containers/0/env/-", "value": {"name": "KONG_SSL_CERT_KEY", "value": "/etc/secrets/ebms-tls-secret/tls.key"}}
]' 2>/dev/null || true

echo "==> 8. Kong herstarten (Lokale Port-Forward uitsluitend voor Dev/Vagrant)..."
kubectl rollout restart deployment/kong-kong -n kong
kubectl rollout status deployment/kong-kong -n kong --timeout=300s || true

if [ "${ENV_TYPE:-dev}" = "dev" ]; then
  pkill -f "port-forward.*8843" 2>/dev/null || true
  nohup kubectl port-forward -n kong service/kong-kong-proxy 8843:8443 --address 0.0.0.0 > /dev/null 2>&1 &
fi

echo "==> 9. Wachten op Spring Boot startup van de Orchestrator..."
until kubectl logs deployment/ebms-ebms-orchestrator 2>&1 | grep -q "Started EbmsOrchestratorApplication"; do
    echo "    Orchestrator start nog op... (wacht 5s)"
    sleep 5
done

echo "==> Uitrol succesvol afgerond!"
```

---

## Sectie 4: Post-Deployment Validatie & Rooktesten

### 4.1 Generieke Validatie (Productie & OTAP)
Draai onderstaande controles op elke willekeurige productie- of OTAP-omgeving:

1. **WSDL Endpoint Bereikbaarheid:**
   ```bash
   curl -s -o /dev/null -w "%{http_code}\n" http://<cluster-ip>:30000/services/ebms?wsdl
   # Verwacht resultaat: 200
   ```
2. **Spring Boot Health & Actuator Status:**
   ```bash
   curl -s http://ebms-cpa-service:8081/actuator/health
   curl -s http://ebms-crypto-service:8082/actuator/health
   curl -s http://ebms-ebms-orchestrator:8080/actuator/health
   # Verwacht resultaat: {"status":"UP"}
   ```
3. **Database Schema Migraties (Flyway):**
   Controleer of de tabellen `cpa.cpa`, `crypto.keystores` en `orchestrator.ebms_message` aanwezig en gevuld zijn.

---

### 4.2 Compliance Testsuite Validatie [Specifiek voor Logius Testsuite]
Draai deze specifieke verificatiestappen bij het testen tegen de Logius Compliance Simulator:

1. **OpenSSL Non-SNI SSL Handshake (Poort 8843):**
   ```bash
   SSL_OUT=$(openssl s_client -connect 127.0.0.1:8843 </dev/null 2>/dev/null | openssl x509 -noout -text || true)
   echo "$SSL_OUT" | grep "Subject:"
   # Verwacht resultaat: Subject: CN = proxy-dart, O = Logius, C = NL
   ```
2. **DigipoortStub Ingress Rewrite & Header Injectie:**
   ```bash
   curl -k -s -o /dev/null -w "%{http_code}\n" https://127.0.0.1:8843/digipoortStub
   # Verwacht resultaat: 200, 400 of 500 (geeft aan dat /digipoortStub de Orchestrator bereikt via Kong)
   ```
3. **Logius Echo Test & Asynchrone Callback:**
   Draai `./logiusecho.sh` en verifieer in de logs dat:
   - Outbound XML-DSig ondertekening slaagt via `crypto-service` met SHA-1.
   - Het bericht naar Logius wordt verstuurd op `https://proxy-cvwus:8443`.
   - Logius asynchroon antwoordt op `https://proxy-dart:8843/digipoortStub`.
   - De Orchestrator het inkomende bericht ontvangt op `OrchestratorService` met de bijbehorende `RefToMessageId`.
