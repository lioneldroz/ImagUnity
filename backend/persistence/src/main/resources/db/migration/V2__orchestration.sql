-- Phase 4 : orchestrateur (paquets d'un transfert, partenaires appairés)

ALTER TABLE transfer ALTER COLUMN contact RENAME TO destination;
ALTER TABLE transfer ADD COLUMN package_group_id VARCHAR(64);
ALTER TABLE transfer ADD COLUMN part_count INT DEFAULT 0 NOT NULL;
UPDATE transfer SET size_bytes = 0 WHERE size_bytes IS NULL;
ALTER TABLE transfer ALTER COLUMN size_bytes SET NOT NULL;

CREATE INDEX idx_transfer_study ON transfer (study_uid, called_aet);

CREATE TABLE transfer_part (
    transfer_id        UUID          NOT NULL REFERENCES transfer (id) ON DELETE CASCADE,
    part_index         INT           NOT NULL,
    part_count         INT           NOT NULL,
    file_path          VARCHAR(1024) NOT NULL,
    size_bytes         BIGINT        NOT NULL,
    sha256             CHAR(64)      NOT NULL,
    transport_file_id  BIGINT,
    uploaded           BOOLEAN       DEFAULT FALSE NOT NULL,
    PRIMARY KEY (transfer_id, part_index)
);

CREATE INDEX idx_transfer_part_file ON transfer_part (transport_file_id);

CREATE TABLE peer (
    name               VARCHAR(64)   PRIMARY KEY,
    transport_peer_id  BIGINT        NOT NULL UNIQUE,
    display_name       VARCHAR(255),
    paired_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
