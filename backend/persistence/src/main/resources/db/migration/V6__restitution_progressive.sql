-- Restitution au fil de l'eau : paquets déjà restitués d'un transfert reçu, et instances attendues de l'étude.
ALTER TABLE inbound_transfer ADD COLUMN restored_parts VARCHAR(40000) DEFAULT '' NOT NULL;
ALTER TABLE inbound_transfer ADD COLUMN expected_instances INT DEFAULT -1 NOT NULL;
