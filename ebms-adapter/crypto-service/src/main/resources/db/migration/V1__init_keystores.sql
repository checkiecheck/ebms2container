-- =============================================================================
-- crypto-service – database-initialisatie
-- Flyway migration: V1__init_keystores.sql
--
-- Schema voor sleutel-metadata en crypto-auditlogs.
-- De sleutels zelf worden NOOIT in de database opgeslagen;
-- ze leven uitsluitend in de PKCS12 KeyStore op het bestandssysteem.
-- =============================================================================

-- =============================================================================
-- Tabel: key_pair_metadata
-- Metadata over de sleutelparen in de KeyStore (niet de sleutels zelf!)
-- =============================================================================
CREATE TABLE IF NOT EXISTS key_pair_metadata (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    alias           VARCHAR(255)  NOT NULL,

    -- Sleutel-type en algoritme
    key_type        VARCHAR(50)   NOT NULL,       -- RSA | EC
    key_size        INTEGER,                       -- RSA: 2048/4096 | EC: 256/384/521
    algorithm       VARCHAR(100)  NOT NULL,        -- bijv. RSA-SHA256, ECDSA-SHA256

    -- Geldigheid
    valid_from      TIMESTAMPTZ   NOT NULL,
    valid_until     TIMESTAMPTZ,

    -- Certificaat-fingerprint (SHA-256 hex)
    fingerprint     VARCHAR(100),

    -- Gebruik
    key_usage       VARCHAR(50),                   -- SIGNING | ENCRYPTION | SIGNING_ENCRYPTION

    -- Status
    status          VARCHAR(50)   NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | EXPIRED | REVOKED | PENDING_ACTIVATION

    -- Vervanging
    superseded_by   VARCHAR(255),                  -- alias van het nieuwe sleutelpaar

    -- Audit
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_key_alias UNIQUE (alias)
);

-- =============================================================================
-- Tabel: crypto_audit_log
-- Append-only auditlog voor alle cryptografische operaties.
-- Geen DELETE/UPDATE rechten nodig op deze tabel.
-- =============================================================================
CREATE TABLE IF NOT EXISTS crypto_audit_log (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Operatie-type
    operation       VARCHAR(100)  NOT NULL,
    -- XML_SIGN | XML_VERIFY | XML_ENCRYPT | XML_DECRYPT | C14N | KEY_LOAD

    -- Context
    key_alias       VARCHAR(255),
    message_id      VARCHAR(255),
    cpa_id          VARCHAR(255),
    party_id        VARCHAR(255),

    -- Resultaat
    result          VARCHAR(50)   NOT NULL,        -- SUCCESS | FAILURE
    error_code      VARCHAR(100),
    error_detail    TEXT,

    -- Duur (voor performance-monitoring)
    duration_ms     INTEGER,

    -- Tijdstip
    performed_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- Indexen
-- =============================================================================
CREATE INDEX idx_key_alias       ON key_pair_metadata (alias);
CREATE INDEX idx_key_status      ON key_pair_metadata (status);
CREATE INDEX idx_key_valid       ON key_pair_metadata (valid_until);

CREATE INDEX idx_audit_operation  ON crypto_audit_log (operation);
CREATE INDEX idx_audit_message_id ON crypto_audit_log (message_id);
CREATE INDEX idx_audit_performed  ON crypto_audit_log (performed_at DESC);
CREATE INDEX idx_audit_result     ON crypto_audit_log (result) WHERE result = 'FAILURE';

-- =============================================================================
-- Trigger: updated_at voor key_pair_metadata
-- =============================================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_key_updated_at
    BEFORE UPDATE ON key_pair_metadata
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- =============================================================================
-- Security: beperk rechten op audit-log (append-only principe)
-- Pas aan naar de juiste DB-gebruiker in productie
-- =============================================================================
-- REVOKE UPDATE, DELETE ON crypto_audit_log FROM ebms_crypto;
-- (Bovenstaande activeren in productie-migraties)

-- =============================================================================
-- Commentaar
-- =============================================================================
COMMENT ON TABLE  key_pair_metadata            IS 'Metadata over PKCS12-sleutelparen; sleutels zelf staan NIET in de DB';
COMMENT ON COLUMN key_pair_metadata.alias      IS 'KeyStore-alias (uniek per service-instantie)';
COMMENT ON COLUMN key_pair_metadata.fingerprint IS 'SHA-256 fingerprint van het bijbehorende certificaat';
COMMENT ON TABLE  crypto_audit_log             IS 'Append-only auditlog voor alle XML-DSig/Enc operaties (non-repudiation)';
