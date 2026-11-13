-- Configuration centralisée (docs/configuration-centralisee.md) : documents signés par le responsable, et dernier
-- compte rendu de chaque box (version appliquée, dernier refus).

CREATE TABLE configuration_document (
    sequence      BIGINT       PRIMARY KEY,           -- numéro de version, croissant pour l'organisation
    document      TEXT         NOT NULL,              -- document signé, octets exacts
    issued_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE configuration_report (
    box_id        UUID         PRIMARY KEY,
    applied       BIGINT       NOT NULL,
    applied_at    TIMESTAMP WITH TIME ZONE,
    refused       BIGINT       NOT NULL,
    refusal       VARCHAR(500),
    reported_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
