-- Verrou des publications d'entrées : une seule ligne, verrouillée (SELECT … FOR UPDATE) par chaque publication
-- jusqu'à la fin de sa transaction. Les numéros d'ordre (seq) sont ainsi validés dans l'ordre où ils sont tirés :
-- sans cela, une publication plus lente, de numéro plus petit, pouvait devenir visible après qu'un lecteur avait déjà
-- retenu un curseur plus grand, et ce lecteur ne la relisait qu'à sa lecture complète suivante (jusqu'à 24 h).
CREATE TABLE publication_lock (
    id INT PRIMARY KEY
);
INSERT INTO publication_lock (id) VALUES (1);
