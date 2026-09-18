# Deployment Guide – HAVEN k3s / OpenShift

> Operationele bevindingen uit de HAVEN k3s-omgeving (v9-deployment). Dit document synchroniseert
> platform-specifieke afwijkingen t.o.v. het standaard Helm-chart in `helm/` en moet worden gelezen
> vóór elke nieuwe deploy naar HAVEN.

---

## 1. Keystore Mount Correction (KRITIEK)

**Probleem geconstateerd in v9:** `crypto-service` en `ebms-orchestrator` verwachten hun
PKCS12 keystore/truststore op `/app/keystores` (zie `KEYSTORE_PATH`, `TRUSTSTORE_PATH` env-vars).
Het standaard Helm-chart (`helm/charts/crypto-service/templates/deployment.yaml`) mount hiervoor
echter een **lege PersistentVolumeClaim** (`keystore-volume` → PVC `<release>-crypto-service-keystore`).
Zonder een extra, handmatige stap om het PKCS12-bestand ná deploy in de PVC te kopiëren, is de
keystore-directory leeg en faalt zowel signing (crypto-service) als outbound mTLS (ebms-orchestrator,
zie `EbmsOutboundSSLProperties`) stil (graceful fallback naar "geen SSLContext"/geen signing-key).

**Vereiste correctie:** mount een Kubernetes **Secret Volume** (niet de PVC) direct op
`/app/keystores`, gevuld vanuit een vooraf aangemaakt `crypto-keystore-secret`:

```bash
# Secret aanmaken met het PKCS12-bestand als binary data
kubectl create secret generic crypto-keystore-secret \
  --namespace <namespace> \
  --from-file=keystore.p12=./keystore.p12 \
  --from-file=truststore.p12=./truststore.p12
```

Volume-definitie die de PVC-mount in `deployment.yaml` (crypto-service én ebms-orchestrator)
moet vervangen:

```yaml
volumes:
  - name: keystore-volume
    secret:
      secretName: crypto-keystore-secret
      defaultMode: 0440
volumeMounts:
  - name: keystore-volume
    mountPath: /app/keystores
    readOnly: true
```

**Toepassen op HAVEN (tot het chart zelf is aangepast):** patch de gegenereerde manifesten na
`helm template`, of gebruik een Kustomize-overlay die de `volumes[]`/`volumeMounts[]` van beide
Deployments vervangt door het bovenstaande Secret-volume. **Nog niet opgelost in het chart zelf** —
zie backlog-item "mTLS/Keystore Helm Wiring".

---

## 2. Port / Ingress Updates (Kong)

De HAVEN Kong Ingress Controller routeert per pad naar de juiste backend-service. Alle routes
draaien op **dezelfde host/Ingress-resource** als `ebms-orchestrator`
(`helm/charts/ebms-orchestrator/templates/ingress.yaml`), met `konghq.com/strip-path: "false"`
zodat het volledige pad behouden blijft richting de backend:

| Pad             | pathType | Backend-service                  | Doel                                   |
|-----------------|----------|-----------------------------------|-----------------------------------------|
| `/soap/ebms`    | Prefix   | `ebms-orchestrator`               | ebMS2 SOAP-endpoint (partner-verkeer)   |
| `/admin`        | Prefix   | `ebms-orchestrator`               | Admin-dashboard (`/admin/index.html`)   |
| `/api/admin`    | Prefix   | `ebms-orchestrator`               | Admin REST-API (`/api/admin/messages`)  |
| `/api/cpa`      | Prefix   | `<release-name>-cpa-service`      | CPA-beheer (upload/ophalen vanuit dashboard) |

Deze routes staan gedefinieerd in `helm/values.yaml` onder `ebms-orchestrator.ingress.hosts[0].paths`
(`/soap/ebms`, `/admin`, `/api/admin`) en `ebms-orchestrator.ingress.cpaServiceRoute` (`/api/cpa`).

> **Let op:** de eerder gedocumenteerde `CPA_SERVICE_URL` default (`http://cpa-service:8081`) komt
> niet overeen met de werkelijke Helm-fullname van `cpa-service` (`<release-name>-cpa-service`,
> zonder `nameOverride`). Zie `cpaServiceRoute.serviceName` override indien de release-naam afwijkt.

---

## 3. E2E-validatie na installatie

Verifieer een volledige outbound → inbound flow (signing + routing) met de volgende stappen:

### 3.1 Health-checks
```bash
curl -sf https://<host>/soap/ebms/services       # CXF service-lijst (SOAP endpoint actief)
curl -sf https://<host>/admin/index.html          # Admin-dashboard laadt
curl -sf https://<host>/api/admin/messages         # Lege/gevulde paginated JSON-response
```

### 3.2 CPA laden (voorwaarde voor signing-test)
1. Open `/admin/index.html` → tab **CPA Beheer**.
2. Upload een CPA XML met `osb-be-s`- of `osb-rm-s`-profiel (vereist signing) voor twee partijen.
3. Controleer dat de netwerk-kaart beide partijen + OIN's correct toont (zie fix §"Unknown Party").

### 3.3 Outbound signing-verificatie
1. Trigger een outbound bericht (via `OutboundMessageService`/RabbitMQ) voor een CPA met een
   `s`-profiel (signing vereist).
2. Controleer in crypto-service logs: `[XML-SIGN] Ondertekend: keyAlias=... algo=...`.
3. **Kritieke check (deze fix):** inspecteer de verzonden SOAP-envelop — het `<ds:Signature>`-element
   moet zich **binnen `<soapenv:Header>`** bevinden, niet als sibling van `Header`/`Body` direct onder
   `<soapenv:Envelope>`. Vóór de fix stond de handtekening onder de root, wat een schema-violation
   veroorzaakte en door SAAJ/CXF bij de ontvanger stilletjes werd verworpen (bericht leek onondertekend).
4. Open `/admin/index.html` → tab **Berichten** → klik de rij van het verzonden bericht → controleer
   `Raw SOAP XML` in de details-modal om de Header-plaatsing visueel te bevestigen.

### 3.4 Inbound verificatie
1. Stuur het ondertekende bericht (of een testbericht van een partner) naar `/soap/ebms/ebms`.
2. Controleer dat `OrchestratorService` de handtekening succesvol verifieert
   (`CryptoServiceClient.verify()` → geen `XmlSecurityException`).
3. Bevestig in de **Berichten**-tab dat het bericht verschijnt met `direction=INBOUND` — het
   admin-endpoint toont dit standaard mee (geen richting-filter nodig, optioneel via
   `?direction=INBOUND`).

### 3.5 mTLS-verificatie (outbound)
1. Controleer bij het opstarten van `ebms-orchestrator` de logs:
   - Met correcte Secret-volume-mount (§1): `[OUTBOUND] mTLS SSLContext opgebouwd (keystore=/app/keystores/...)`
   - Zonder geldige keystore: `Outbound SSLContext not configured - falling back to plain HTTP/HTTPS`
2. Bij een partner-endpoint dat mTLS afdwingt, moet de eerste log-variant zichtbaar zijn — anders is
   de Secret-volume-mount (§1) niet correct toegepast.

---

## Referenties
- `helm/values.yaml` – Kong-annotaties, paden, `cpaServiceRoute`
- `helm/charts/ebms-orchestrator/templates/ingress.yaml` – route-definities
- `crypto-service/.../XmlSigningService.java` – SOAP-aware signing (Header-plaatsing)
- `ebms-orchestrator/.../soap/EbmsOutboundSSLProperties.java` – mTLS keystore/truststore-config
