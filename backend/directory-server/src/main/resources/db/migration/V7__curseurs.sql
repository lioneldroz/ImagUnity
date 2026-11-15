-- Lecture par différence (étape 4 de la fédération) : chaque entrée publiée ou relayée reçoit un numéro d'ordre
-- croissant ; un lecteur demande « depuis le curseur N » et ne reçoit que ce qui a changé.
CREATE SEQUENCE directory_seq;

ALTER TABLE box_entry ADD COLUMN seq BIGINT;
UPDATE box_entry SET seq = nextval('directory_seq');
ALTER TABLE box_entry ALTER COLUMN seq SET NOT NULL;
CREATE INDEX box_entry_seq ON box_entry (seq);

ALTER TABLE partner_entry ADD COLUMN seq BIGINT;
UPDATE partner_entry SET seq = nextval('directory_seq');
ALTER TABLE partner_entry ALTER COLUMN seq SET NOT NULL;
CREATE INDEX partner_entry_seq ON partner_entry (partner_fingerprint, seq);
