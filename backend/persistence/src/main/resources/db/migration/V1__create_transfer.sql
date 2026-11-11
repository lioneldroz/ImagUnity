-- État des transferts d'examens (phase 4 : orchestrateur)
CREATE TABLE transfer (
    id               UUID         PRIMARY KEY,
    study_uid        VARCHAR(64)  NOT NULL,
    called_aet       VARCHAR(16)  NOT NULL,
    contact          VARCHAR(255) NOT NULL,
    state            VARCHAR(16)  NOT NULL,
    size_bytes       BIGINT,
    error_message    VARCHAR(2000),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_transfer_state ON transfer (state);
