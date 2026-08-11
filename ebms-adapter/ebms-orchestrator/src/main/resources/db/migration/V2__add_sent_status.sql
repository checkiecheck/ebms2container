-- =============================================================================
-- ebms-orchestrator – Flyway V2
-- Voegt SENT-status toe aan de ebms_message_status enum
--
-- SENT: bericht verzonden naar partner; wacht op ebMS2 Acknowledgment (osb-rm)
-- =============================================================================

ALTER TYPE ebms_message_status ADD VALUE IF NOT EXISTS 'SENT';

COMMENT ON TYPE ebms_message_status IS
    'Lifecycle: RECEIVED→PROCESSING→[SENT→]DELIVERED/ACKNOWLEDGED/FAILED/DUPLICATE';
