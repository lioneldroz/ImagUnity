-- Fédération : reconnaissances de partenaires publiées par le responsable (historique compris), et entrées des
-- partenaires relayées depuis leur annuaire (dernière entrée vérifiée de chaque box).

CREATE TABLE recognition (
    partner_fingerprint   VARCHAR(64)   NOT NULL,
    serial                BIGINT        NOT NULL,
    partner_organization  VARCHAR(64)   NOT NULL,
    revoked               BOOLEAN       NOT NULL,
    valid_until           TIMESTAMP WITH TIME ZONE NOT NULL,
    document              TEXT          NOT NULL,
    published_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (partner_fingerprint, serial)
);

CREATE TABLE partner_entry (
    partner_fingerprint   VARCHAR(64)   NOT NULL,
    box_id                UUID          NOT NULL,
    serial                BIGINT        NOT NULL,
    document              TEXT          NOT NULL,
    fetched_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (partner_fingerprint, box_id)
);
