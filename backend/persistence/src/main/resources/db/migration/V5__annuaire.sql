-- Annuaire signé (étape C) : partenaire lié à une box enrôlée, cache des entrées vérifiées, numéros déjà vus.

ALTER TABLE peer ADD COLUMN box_id UUID;

-- Dernière entrée vérifiée de chaque box (document signé tel que reçu de l'annuaire) : la box fonctionne même
-- si l'annuaire est momentanément injoignable.
CREATE TABLE directory_entry (
    box_id      UUID          PRIMARY KEY,
    serial      BIGINT        NOT NULL,
    document    CLOB          NOT NULL,
    fetched_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Plus haut numéro d'émission vu pour chaque box : refus des retours en arrière.
CREATE TABLE directory_serial (
    box_id  UUID    PRIMARY KEY,
    serial  BIGINT  NOT NULL
);
