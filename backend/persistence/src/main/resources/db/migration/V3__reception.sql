-- Phase 5 : réception (fichiers proposés par les partenaires, transferts annoncés)

CREATE TABLE inbound_file (
    transport_file_id  BIGINT        PRIMARY KEY,
    peer_name          VARCHAR(64)   NOT NULL,
    file_name          VARCHAR(255)  NOT NULL,
    size_bytes         BIGINT        NOT NULL,
    state              VARCHAR(16)   NOT NULL,
    file_path          VARCHAR(1024),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_inbound_file_name ON inbound_file (peer_name, file_name);

CREATE TABLE inbound_transfer (
    transfer_id        UUID          NOT NULL,
    peer_name          VARCHAR(64)   NOT NULL,
    package_group_id   VARCHAR(64)   NOT NULL,
    study_uid          VARCHAR(64)   NOT NULL,
    part_count         INT           NOT NULL,
    announcement       CLOB          NOT NULL,
    state              VARCHAR(16)   NOT NULL,
    error_message      VARCHAR(2000),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (peer_name, transfer_id)
);

CREATE INDEX idx_inbound_transfer_group ON inbound_transfer (peer_name, package_group_id);
CREATE INDEX idx_inbound_transfer_state ON inbound_transfer (state);
