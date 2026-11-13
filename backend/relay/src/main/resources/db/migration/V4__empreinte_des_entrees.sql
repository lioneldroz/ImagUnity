-- Empreinte du contenu de l'entrée retenue au plus haut numéro : une autre entrée du même numéro est refusée.
ALTER TABLE directory_serial ADD COLUMN digest VARCHAR(64);
