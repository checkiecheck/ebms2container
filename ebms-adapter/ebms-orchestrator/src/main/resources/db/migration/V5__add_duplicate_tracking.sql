-- =============================================================================
-- ebms-orchestrator – Flyway V5
-- Voegt duplicate_count/last_duplicate_at toe aan ebms_message
--
-- Nodig voor: DevAgent-audit scenario I-7 (Dubbel Bericht). De originele rij mag
-- bij een duplicaat NIET overschreven worden (status/content blijven ongewijzigd,
-- de uq_message_id-constraint staat een tweede rij niet toe) - deze kolommen maken
-- duplicaat-pogingen alsnog zichtbaar zonder de bestaande rij aan te tasten.
-- =============================================================================

ALTER TABLE ebms_message ADD COLUMN IF NOT EXISTS duplicate_count INT NOT NULL DEFAULT 0;
ALTER TABLE ebms_message ADD COLUMN IF NOT EXISTS last_duplicate_at TIMESTAMPTZ;

COMMENT ON COLUMN ebms_message.duplicate_count IS
    'Aantal keer dat dit messageId als duplicaat is aangeboden (originele rij blijft ongewijzigd)';
COMMENT ON COLUMN ebms_message.last_duplicate_at IS
    'Tijdstip van de laatst gedetecteerde duplicaat-aanbieding voor dit messageId';
