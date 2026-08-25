-- =============================================================================
-- ebms-orchestrator – Flyway V4
-- Voegt error_message toe aan ebms_message
--
-- Nodig voor:
--  - Task 2 (Inbound OIN-validatie / anti-spoofing): vastleggen waarom een bericht
--    met status=FAILED werd afgewezen (SecurityFailure).
--  - Task 3 (Watchdog reconciliator): vastleggen waarom een vastgeplakt PROCESSING-
--    bericht automatisch als FAILED werd gemarkeerd.
-- =============================================================================

ALTER TABLE ebms_message ADD COLUMN IF NOT EXISTS error_message TEXT;

COMMENT ON COLUMN ebms_message.error_message IS
    'Foutmelding bij status=FAILED (bijv. OIN-antispoofing of watchdog-timeout)';
