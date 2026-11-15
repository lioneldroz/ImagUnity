-- Versions du protocole (étape 5 de la fédération) : le compte rendu signé par la box est gardé tel quel. Le
-- responsable y lit, au renouvellement de l'entrée, les versions du protocole que la box annonce.
ALTER TABLE configuration_report ADD COLUMN document TEXT;
