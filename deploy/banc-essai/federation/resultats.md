# Banc fédération — 2026-11-15 18:32

| Essai | Résultat | Détail |
|---|---|---|
| Machines séparées | réussi | fichiers déposés chez A : annuaire/config/fraicheur/delegation.json, annuaire/config/fraicheur/fraicheur.json, annuaire/config/organisation.pub.json, box/config/organisation.pub.json, relais/config/organisation.pub.json ; clé privée et codes seulement sur le poste du responsable |
| Enrôlement des deux box | réussi | A = 35489970-d28a-42cb-a8e7-dc7c680e14c4, B = e56dff59-0980-4812-98e0-db8dd3c3e8cb |
| Versions du protocole annoncées | réussi | entrées signées : A ['iu1.0'], B ['iu1.0'] |
| Reconnaissance mutuelle | réussi | chaque box voit l'autre comme box partenaire utilisable |
| Preuves de fraîcheur | réussi | chaque box a la preuve de son annuaire et celle du partenaire ; fraicheur-verifier : preuves conformes |
| Lecture réservée des annuaires | réussi | listes sans compte : [401], entrée d'une box : [200], compte de lecture : [200] ; box, relais et annuaire partenaire lisent par lecture signée ou compte |
| Appairage entre organisations | réussi | partenaires vus par B : ['box-a'] |
| Examen A-vers-B : A → B | réussi | ACKNOWLEDGED, 5 / 5 instances intactes au PACS B, 34 s |
| Examen B-vers-A : B → A | réussi | ACKNOWLEDGED, 5 / 5 instances intactes au PACS A, 32 s |
| Refus : box locale hors du périmètre | réussi | Partenaire « box-b » : Cette box n'est pas dans les groupes autorisés à échanger avec « bourgogne-fc » |
| Refus : relais du partenaire non autorisé | réussi | Partenaire « box-b » : Box « Box B » sur un relais non autorisé par l'accord avec « bourgogne-fc » |
| Refus : accord à sens unique | réussi | dépôt refusé par le relais de B, signalé par la box A, message gardé |
| Refus : identifiant de box revendiqué par l'autre organisation | réussi | bourgogne-fc (WKVA-6B6E-FHBN-WS6P-M3QM-E6X4) écartée, tenu par cette organisation |
| Refus : annuaire du partenaire figé | réussi | Partenaire « box-b » : Annuaire de « bourgogne-fc » sans preuve de fraîcheur récente (dernière : 2026-11-15T18:30:08.777369972Z) : échanges arrêtés jusqu'à sa prochaine lecture |
| Refus : accord révoqué | réussi | Partenaire « box-b » : Accord avec « bourgogne-fc » révoqué |
