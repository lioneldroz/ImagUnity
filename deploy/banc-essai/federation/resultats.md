# Banc fédération — 2026-11-15 15:44

| Essai | Résultat | Détail |
|---|---|---|
| Enrôlement des deux box | réussi | A = 75e027e1-ad65-47d2-86df-ee8a12a5989a, B = c7ce9e91-a362-4f15-b5c1-ead5b8a1c248 |
| Reconnaissance mutuelle | réussi | chaque box voit l'autre comme box partenaire utilisable |
| Preuves de fraîcheur | réussi | chaque box a la preuve de son annuaire et celle du partenaire ; fraicheur-verifier : preuves conformes |
| Appairage entre organisations | réussi | partenaires vus par B : ['box-a'] |
| Examen A-vers-B : A → B | réussi | ACKNOWLEDGED, 5 / 5 instances intactes au PACS B, 34 s |
| Examen B-vers-A : B → A | réussi | ACKNOWLEDGED, 5 / 5 instances intactes au PACS A, 32 s |
| Refus : box locale hors du périmètre | réussi | Partenaire « box-b » : Cette box n'est pas dans les groupes autorisés à échanger avec « bourgogne-fc » |
| Refus : relais du partenaire non autorisé | réussi | Partenaire « box-b » : Box « Box B » sur un relais non autorisé par l'accord avec « bourgogne-fc » |
| Refus : accord à sens unique | réussi | dépôt refusé par le relais de B, signalé par la box A, message gardé |
| Refus : identifiant de box revendiqué par l'autre organisation | réussi | bourgogne-fc (WWK5-CNDX-NCU2-HNAV-4SYX-42FR) écartée, tenu par cette organisation |
| Refus : annuaire du partenaire figé | réussi | Partenaire « box-b » : Annuaire de « bourgogne-fc » sans preuve de fraîcheur récente (dernière : 2026-11-15T15:41:26.288154567Z) : échanges arrêtés jusqu'à sa prochaine lecture |
| Refus : accord révoqué | réussi | Partenaire « box-b » : Accord avec « bourgogne-fc » révoqué |
