-- Demandes d'enrôlement envoyées par les box, en attente de la signature du responsable (une par box : la plus
-- récente remplace la précédente). Effacées à la publication de l'entrée de la box, ou après quelques jours.

CREATE TABLE enrollment_request (
    box_id        UUID          PRIMARY KEY,
    display_name  VARCHAR(256)  NOT NULL,
    fingerprint   VARCHAR(64)   NOT NULL,             -- empreinte courte de la box, à comparer hors bande au besoin
    code_id       VARCHAR(32),                        -- identifiant du code d'enrôlement, si la box en a un
    submission    TEXT          NOT NULL,             -- demande transmise, octets exacts
    received_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
