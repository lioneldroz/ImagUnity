-- Annuaire : toutes les entrées publiées, historique compris (une ligne par box et par numéro d'émission).

CREATE TABLE box_entry (
    box_id        UUID          NOT NULL,
    serial        BIGINT        NOT NULL,
    display_name  VARCHAR(256)  NOT NULL,
    revoked       BOOLEAN       NOT NULL,
    valid_until   TIMESTAMP WITH TIME ZONE NOT NULL,
    document      TEXT          NOT NULL,
    published_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (box_id, serial)
);
