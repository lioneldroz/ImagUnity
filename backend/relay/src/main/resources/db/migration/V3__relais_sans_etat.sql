-- Relais sans état (forme 2) : plusieurs instances derrière un répartiteur partagent cette base.

-- Anti-rejeu des requêtes signées (spécification § 6.3) : identifiant de requête vu, gardé jusqu'à la sortie de son
-- horodatage de la fenêtre admise. Une requête rejouée vers une autre instance est refusée comme sur la même.
CREATE TABLE request_seen (
    request_id  VARCHAR(32)  PRIMARY KEY,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_request_seen_expires ON request_seen (expires_at);

-- Baux des tâches à ne faire tourner que sur une instance à la fois (purge).
CREATE TABLE relay_lease (
    name        VARCHAR(40)  PRIMARY KEY,
    holder      VARCHAR(100) NOT NULL,
    until       TIMESTAMP WITH TIME ZONE NOT NULL
);
