# Banc fédération — 2026-11-15 16:16

| Essai | Résultat | Détail |
|---|---|---|
| Enrôlement des deux box | réussi | A = a1255130-c444-411a-b4a4-dbc7a6529bcb, B = 10451743-c4cf-46f5-a2d5-11c39a53be2d |
| Reconnaissance mutuelle | réussi | chaque box voit l'autre comme box partenaire utilisable |
| Preuves de fraîcheur | réussi | chaque box a la preuve de son annuaire et celle du partenaire ; fraicheur-verifier : preuves conformes |
| Lecture réservée des annuaires | réussi | listes sans compte : [401], entrée d'une box : [200], compte de lecture : [200] ; box, relais et annuaire partenaire lisent par lecture signée ou compte |
| Appairage entre organisations | réussi | partenaires vus par B : ['box-a'] |
| Examen A-vers-B : A → B | réussi | ACKNOWLEDGED, 5 / 5 instances intactes au PACS B, 34 s |
| Examen B-vers-A : B → A | réussi | ACKNOWLEDGED, 5 / 5 instances intactes au PACS A, 34 s |
| Refus : box locale hors du périmètre | réussi | Partenaire « box-b » : Cette box n'est pas dans les groupes autorisés à échanger avec « bourgogne-fc » |
| Refus : relais du partenaire non autorisé | réussi | Partenaire « box-b » : Box « Box B » sur un relais non autorisé par l'accord avec « bourgogne-fc » |
| Refus : accord à sens unique | réussi | dépôt refusé par le relais de B, signalé par la box A, message gardé |
| Refus : identifiant de box revendiqué par l'autre organisation | réussi | bourgogne-fc (6Z6T-U6P5-OVVD-VBFI-G6YM-B3HM) écartée, tenu par cette organisation |
| Refus : annuaire du partenaire figé | réussi | Partenaire « box-b » : Annuaire de « bourgogne-fc » sans preuve de fraîcheur récente (dernière : 2026-11-15T16:13:25.031533108Z) : échanges arrêtés jusqu'à sa prochaine lecture |
| Refus : accord révoqué | réussi | Partenaire « box-b » : Accord avec « bourgogne-fc » révoqué |
