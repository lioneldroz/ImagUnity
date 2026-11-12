-- Relais IU-T, IU-MSG : files de réception des box et messages en attente (enveloppes chiffrées, opaques).

CREATE TABLE queue (
    recv_id     VARCHAR(43)  PRIMARY KEY,             -- relève (capacité de la box propriétaire)
    send_id     VARCHAR(43)  NOT NULL UNIQUE,         -- dépôt (publié dans l'annuaire)
    box_id      UUID         NOT NULL,                -- box propriétaire (limites)
    queue_key   VARCHAR(100) NOT NULL,                -- clé publique Ed25519 de la file (X.509, base64url)
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_queue_box ON queue (box_id);

CREATE TABLE queue_message (
    message_id   VARCHAR(22)  PRIMARY KEY,
    recv_id      VARCHAR(43)  NOT NULL REFERENCES queue (recv_id) ON DELETE CASCADE,
    sender_box   UUID         NOT NULL,               -- box déposante (limites, abus)
    content      BYTEA        NOT NULL,               -- enveloppe chiffrée pour la box destinataire
    received_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_queue_message_queue ON queue_message (recv_id, received_at);
CREATE INDEX idx_queue_message_expires ON queue_message (expires_at);
