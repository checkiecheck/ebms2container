-- =============================================================================
-- ebms-orchestrator – Flyway V3
-- Voegt optimistic-locking versiekolom toe aan ebms_message
--
-- Zonder deze kolom kan een RabbitMQ-redelivery of gelijktijdige status-update
-- een lost-update veroorzaken: een oudere transactie (bijv. die nog PROCESSING
-- zet) kan een nieuwere, verder gevorderde status (bijv. DELIVERED) overschrijven
-- zonder dat Hibernate dit detecteert. Met @Version faalt de oudere UPDATE met
-- een OptimisticLockException in plaats van de data stilletjes te corrumperen.
-- =============================================================================

ALTER TABLE ebms_message ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN ebms_message.version IS
    'Optimistic-locking versie (JPA @Version) - voorkomt lost-updates bij concurrent verwerking';
