-- Phase 6 : accusé de bout en bout (côté réception : instances restituées, accusé envoyé)

ALTER TABLE inbound_transfer ADD COLUMN restored_instances INT DEFAULT 0 NOT NULL;
ALTER TABLE inbound_transfer ADD COLUMN ack_sent BOOLEAN DEFAULT FALSE NOT NULL;

CREATE INDEX idx_inbound_transfer_ack ON inbound_transfer (ack_sent, state);
