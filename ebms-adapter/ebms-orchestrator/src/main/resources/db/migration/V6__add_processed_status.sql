-- Voeg de terminale status toe voor succesvol verwerkte ebMS-systeemsignalen.
ALTER TYPE ebms_message_status ADD VALUE IF NOT EXISTS 'PROCESSED';
