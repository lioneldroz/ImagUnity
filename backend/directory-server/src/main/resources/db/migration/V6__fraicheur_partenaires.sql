-- Fédération : dernière preuve de fraîcheur de chaque partenaire, relayée depuis son annuaire (délégation et preuve,
-- vérifiées avec la clé du partenaire).
CREATE TABLE partner_freshness (
    partner_fingerprint   VARCHAR(64)   PRIMARY KEY,
    freshness             TEXT          NOT NULL,
    issued_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    fetched_at            TIMESTAMP WITH TIME ZONE NOT NULL
);
