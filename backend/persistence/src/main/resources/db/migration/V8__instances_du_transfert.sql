-- Instances empaquetées dans chaque transfert : une instance reçue après l'empaquetage de son étude (série en retard)
-- part dans un nouveau transfert, au lieu d'être prise pour un reste de réception et supprimée.
CREATE TABLE transfer_instance (
    transfer_id       UUID         NOT NULL REFERENCES transfer (id) ON DELETE CASCADE,
    sop_instance_uid  VARCHAR(64)  NOT NULL,
    PRIMARY KEY (transfer_id, sop_instance_uid)
);
