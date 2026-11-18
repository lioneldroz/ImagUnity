-- Demandes d'essai de connexion (« Tester maintenant ») : la dernière de chaque box, signée par le responsable.
-- La box la lit à son réveil (flux d'événements) ou à sa lecture périodique, et la vérifie elle-même.

CREATE TABLE test_request (
    box_id        UUID         PRIMARY KEY,
    document      TEXT         NOT NULL,              -- demande signée, octets exacts
    requested_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
