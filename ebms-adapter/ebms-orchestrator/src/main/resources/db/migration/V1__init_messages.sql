-- =============================================================================
-- ebms-orchestrator – database-initialisatie
-- Flyway migration: V1__init_messages.sql
--
-- Digikoppeling ebMS2 berichttabel voor Reliable Messaging conform
-- OASIS ebXML Messaging Services v2.0 / Koppelvlakstandaard v3.3.2
-- =============================================================================

-- Enum voor bericht-status (Reliable Messaging lifecycle)
CREATE TYPE ebms_message_status AS ENUM (
    'RECEIVED',       -- Ontvangen, nog niet verwerkt
    'PROCESSING',     -- Wordt momenteel verwerkt
    'DELIVERED',      -- Succesvol afgeleverd aan de backoffice
    'ACKNOWLEDGED',   -- ACK ontvangen van ontvangende partij
    'FAILED',         -- Fout opgetreden, max retries bereikt
    'DUPLICATE'       -- Duplicaat gedetecteerd (al eerder ontvangen)
);

-- Enum voor richting van het bericht
CREATE TYPE ebms_message_direction AS ENUM ('INBOUND', 'OUTBOUND');

-- =============================================================================
-- Hoofdtabel: ebms_message
-- Slaat de volledige bericht-state op conform ebXML MessageHeader
-- =============================================================================
CREATE TABLE IF NOT EXISTS ebms_message (
    -- Interne sleutel
    id                   UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- ebXML MessageHeader velden (conform ISO 15000-2 Section 3.1)
    message_id           VARCHAR(255)    NOT NULL,
    ref_to_message_id    VARCHAR(255),                   -- Verwijzing naar vorig bericht (ACK/Error)
    conversation_id      VARCHAR(255)    NOT NULL,        -- Groepeert berichten in een conversatie
    cpa_id               VARCHAR(255)    NOT NULL,        -- Verwijzing naar de CPA

    -- Party informatie
    from_party_id        VARCHAR(255)    NOT NULL,
    from_party_type      VARCHAR(100),
    from_role            VARCHAR(100),
    to_party_id          VARCHAR(255)    NOT NULL,
    to_party_type        VARCHAR(100),
    to_role              VARCHAR(100),

    -- Service en actie
    service              VARCHAR(255)    NOT NULL,
    service_type         VARCHAR(100),
    action               VARCHAR(100)    NOT NULL,

    -- Bericht-metadata
    direction            ebms_message_direction NOT NULL DEFAULT 'INBOUND',
    status               ebms_message_status    NOT NULL DEFAULT 'RECEIVED',
    timestamp            TIMESTAMPTZ     NOT NULL,
    time_to_live         TIMESTAMPTZ,

    -- Reliable Messaging
    retry_count          SMALLINT        NOT NULL DEFAULT 0,
    last_retry_at        TIMESTAMPTZ,
    ack_requested        BOOLEAN         NOT NULL DEFAULT FALSE,
    duplicate_elimination BOOLEAN        NOT NULL DEFAULT TRUE,

    -- Payload referentie (verwijst naar externe blob-opslag of S3)
    payload_content_type VARCHAR(255),
    payload_ref          VARCHAR(500),

    -- Origineel SOAP-bericht (debugging / non-repudiation)
    raw_soap_xml         TEXT,

    -- Audit-velden
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    -- Uniekheidsconstraint: hetzelfde bericht-ID mag maar éénmaal voorkomen per conversatie
    CONSTRAINT uq_message_id UNIQUE (message_id)
);

-- Indexen voor frequent gebruikte query-patronen
CREATE INDEX idx_ebms_msg_message_id      ON ebms_message (message_id);
CREATE INDEX idx_ebms_msg_conversation    ON ebms_message (conversation_id);
CREATE INDEX idx_ebms_msg_status          ON ebms_message (status);
CREATE INDEX idx_ebms_msg_cpa_id          ON ebms_message (cpa_id);
CREATE INDEX idx_ebms_msg_from_party      ON ebms_message (from_party_id);
CREATE INDEX idx_ebms_msg_to_party        ON ebms_message (to_party_id);
CREATE INDEX idx_ebms_msg_timestamp       ON ebms_message (timestamp DESC);
CREATE INDEX idx_ebms_msg_retry           ON ebms_message (status, retry_count) WHERE status = 'FAILED';

-- Trigger: automatisch updated_at bijhouden
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ebms_message_updated_at
    BEFORE UPDATE ON ebms_message
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Opmerkingen
COMMENT ON TABLE  ebms_message                    IS 'Persistente bericht-state voor ebMS2 Reliable Messaging';
COMMENT ON COLUMN ebms_message.message_id         IS 'Uniek ebXML MessageId (globaal UUID)';
COMMENT ON COLUMN ebms_message.conversation_id    IS 'ebXML ConversationId: groepeert gerelateerde berichten';
COMMENT ON COLUMN ebms_message.cpa_id             IS 'Verwijzing naar de Collaboration Protocol Agreement';
COMMENT ON COLUMN ebms_message.status             IS 'Lifecycle: RECEIVED→PROCESSING→DELIVERED/FAILED';
COMMENT ON COLUMN ebms_message.duplicate_elimination IS 'Activeer duplicate suppression conform ebMS2 profiel';
