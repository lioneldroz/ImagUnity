-- Relais IU-T, IU-FILE : morceaux déposés (contenu sur disque) et leurs destinataires.
-- Le relais ne garde que des identifiants opaques et des clés publiques de capacité : aucun nom de fichier, aucun
-- destinataire nommé.

CREATE TABLE chunk (
    sender_id   VARCHAR(43)  PRIMARY KEY,
    box_id      UUID         NOT NULL,                -- box déposante (quotas, abus)
    sender_key  VARCHAR(100) NOT NULL,                -- clé publique Ed25519 de l'émetteur (X.509, base64url)
    size        INTEGER      NOT NULL,
    digest      CHAR(64)     NOT NULL,
    uploaded    BOOLEAN      NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_chunk_box ON chunk (box_id);
CREATE INDEX idx_chunk_expires ON chunk (expires_at);

CREATE TABLE chunk_recipient (
    recipient_id   VARCHAR(43)  PRIMARY KEY,
    sender_id      VARCHAR(43)  NOT NULL REFERENCES chunk (sender_id) ON DELETE CASCADE,
    recipient_key  VARCHAR(100) NOT NULL,             -- clé publique Ed25519 de retrait (X.509, base64url)
    acked          BOOLEAN      NOT NULL
);
CREATE INDEX idx_chunk_recipient_sender ON chunk_recipient (sender_id);

-- Annuaire de l'organisation, vu du relais : entrées vérifiées et numéros d'émission déjà vus.
CREATE TABLE directory_entry (
    box_id      UUID     PRIMARY KEY,
    serial      BIGINT   NOT NULL,
    document    TEXT     NOT NULL,
    fetched_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE directory_serial (
    box_id  UUID    PRIMARY KEY,
    serial  BIGINT  NOT NULL
);
