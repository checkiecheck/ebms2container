-- =============================================================================
-- cpa-service – database-initialisatie
-- Flyway migration: V1__init_cpa.sql
--
-- Schema voor CPA-documenten, partij-informatie en partner-certificaten
-- conform OASIS ebXML Collaboration Protocol Profile and Agreement v2.0
-- =============================================================================

-- =============================================================================
-- Tabel: collaboration_protocol_agreement
-- Bewaart het volledige CPA-XML-document en de geëxtraheerde metadata
-- =============================================================================
CREATE TABLE IF NOT EXISTS collaboration_protocol_agreement (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    cpa_id         VARCHAR(255)  NOT NULL,
    version        VARCHAR(50),
    description    VARCHAR(500),
    start_date     TIMESTAMPTZ,
    end_date       TIMESTAMPTZ,

    -- Volledig CPA-document (XML conform ebXML CPPA v2.0)
    cpa_xml        TEXT          NOT NULL,

    -- Status
    status         VARCHAR(50)   NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DEPRECATED | REVOKED

    -- Audit
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_cpa_cpa_id UNIQUE (cpa_id)
);

-- =============================================================================
-- Tabel: cpa_party
-- Geëxtraheerde partij-informatie uit de CPA (PartyInfo-element)
-- =============================================================================
CREATE TABLE IF NOT EXISTS cpa_party (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    cpa_id         VARCHAR(255)  NOT NULL
                                 REFERENCES collaboration_protocol_agreement(cpa_id)
                                 ON DELETE CASCADE,

    -- Partij-identificatie
    party_id       VARCHAR(255)  NOT NULL,
    party_id_type  VARCHAR(100),                  -- bijv. 'urn:oasis:names:tc:ebxml-cppa:partyid-type:HIN'

    -- OIN (Organisatie Identificatie Nummer, ISO 6523)
    oin            VARCHAR(20),                   -- 20-cijferig OIN
    oin_validated  BOOLEAN       NOT NULL DEFAULT FALSE,

    -- Rol in de samenwerking
    role           VARCHAR(100),
    service        VARCHAR(255),

    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_cpa_party UNIQUE (cpa_id, party_id)
);

-- =============================================================================
-- Tabel: cpa_delivery_channel
-- Technische afleverkanaal-configuratie (per partij/rol/profiel)
-- =============================================================================
CREATE TABLE IF NOT EXISTS cpa_delivery_channel (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    cpa_id               VARCHAR(255)  NOT NULL
                                       REFERENCES collaboration_protocol_agreement(cpa_id)
                                       ON DELETE CASCADE,
    party_id             VARCHAR(255)  NOT NULL,
    channel_id           VARCHAR(255)  NOT NULL,

    -- Digikoppeling-profiel
    dk_profile           VARCHAR(50)   NOT NULL,
    -- osb-be | osb-rm | osb-be-s | osb-rm-s | osb-be-e | osb-rm-e

    -- Transport
    transport_protocol   VARCHAR(50)   DEFAULT 'HTTP',
    endpoint_url         VARCHAR(500),

    -- Reliable Messaging parameters (alleen bij rm-profielen)
    retry_count          SMALLINT,
    retry_interval       INTEGER,      -- seconden
    persist_duration     INTEGER,      -- seconden (MessageExpiry)

    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_channel UNIQUE (cpa_id, party_id, channel_id)
);

-- =============================================================================
-- Tabel: partner_certificate
-- PKI-certificaten gekoppeld aan CPA-partners (voor XML-DSig-verificatie)
-- =============================================================================
CREATE TABLE IF NOT EXISTS partner_certificate (
    id                   UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    cpa_id               VARCHAR(255)  NOT NULL,
    party_id             VARCHAR(255)  NOT NULL,
    certificate_alias    VARCHAR(255)  NOT NULL,

    -- Certificaat-data (PEM-formaat, X.509)
    certificate_pem      TEXT          NOT NULL,

    -- Geldigheidsperiode (gekopieerd uit certificaat voor snelle filtering)
    valid_from           TIMESTAMPTZ,
    valid_until          TIMESTAMPTZ,

    -- Gebruik
    certificate_usage    VARCHAR(50),  -- SIGNING | ENCRYPTION | SIGNING_ENCRYPTION

    -- Audit
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_cert_alias UNIQUE (cpa_id, party_id, certificate_alias)
);

-- =============================================================================
-- Indexen
-- =============================================================================
CREATE INDEX idx_cpa_cpa_id         ON collaboration_protocol_agreement (cpa_id);
CREATE INDEX idx_cpa_status         ON collaboration_protocol_agreement (status);
CREATE INDEX idx_party_cpa_id       ON cpa_party (cpa_id);
CREATE INDEX idx_party_party_id     ON cpa_party (party_id);
CREATE INDEX idx_party_oin          ON cpa_party (oin);
CREATE INDEX idx_channel_cpa_id     ON cpa_delivery_channel (cpa_id);
CREATE INDEX idx_channel_profile    ON cpa_delivery_channel (dk_profile);
CREATE INDEX idx_cert_cpa_party     ON partner_certificate (cpa_id, party_id);
CREATE INDEX idx_cert_valid         ON partner_certificate (valid_until);

-- =============================================================================
-- Triggers: updated_at
-- =============================================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cpa_updated_at
    BEFORE UPDATE ON collaboration_protocol_agreement
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- =============================================================================
-- Commentaar
-- =============================================================================
COMMENT ON TABLE  collaboration_protocol_agreement IS 'OASIS CPA-documenten (XML) en geëxtraheerde metadata';
COMMENT ON COLUMN collaboration_protocol_agreement.cpa_id IS 'Uniek CPA-identifier conform ebXML CPPA 2.0';
COMMENT ON TABLE  cpa_party                        IS 'Partij-informatie geëxtraheerd uit de CPA (PartyInfo)';
COMMENT ON COLUMN cpa_party.oin                    IS 'Organisatie Identificatie Nummer (ISO 6523, 20 cijfers)';
COMMENT ON TABLE  cpa_delivery_channel             IS 'Technisch afleverkanaal per CPA-partij en Digikoppeling-profiel';
COMMENT ON TABLE  partner_certificate              IS 'PKI-certificaten (X.509/PEM) voor XML-DSig-verificatie';
