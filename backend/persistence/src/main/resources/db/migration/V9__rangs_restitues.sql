-- Rangs restitués d'un transfert reçu : jusqu'à 9999 paquets admis par annonce, soit 48 887 caractères au plus
-- (« 1,2,…,9999 »). La colonne de V6 (40 000) était trop courte pour les plus grosses études.
ALTER TABLE inbound_transfer ALTER COLUMN restored_parts SET DATA TYPE VARCHAR(50000);
