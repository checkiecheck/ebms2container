-- =============================================================================
-- cpa-service – Flyway V2
-- Voegt sync_reply_mode toe aan cpa_delivery_channel
--
-- Nodig voor: ebMS Reliable Messaging - asynchrone Acknowledgments. Volgens de
-- Digikoppeling Koppelvlakstandaard ebMS2 (v3.3+) is async de default
-- (MessagingCharacteristics/@syncReplyMode="none"); synchroon ("mshSignalsOnly")
-- moet expliciet bilateraal in de CPA afgesproken zijn.
-- =============================================================================

ALTER TABLE cpa_delivery_channel ADD COLUMN IF NOT EXISTS sync_reply_mode VARCHAR(50);

COMMENT ON COLUMN cpa_delivery_channel.sync_reply_mode IS
    'ebXML MessagingCharacteristics/@syncReplyMode: none (async, Digikoppeling-default) | mshSignalsOnly | signalsAndResponse';
