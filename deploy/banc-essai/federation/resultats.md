# Banc fédération — 2026-11-17 16:16

| Essai | Résultat | Détail |
|---|---|---|
| Machines séparées | réussi | fichiers déposés chez A : annuaire/config/fraicheur/delegation.json, annuaire/config/fraicheur/fraicheur.json, annuaire/config/organisation.pub.json, box/config/organisation.pub.json, relais/config/organisation.pub.json ; clé privée et codes seulement sur le poste du responsable |
| Enrôlement des deux box | réussi | A = a7b40f9f-adeb-43f1-8591-18ae396708c0, B = 57059c83-10cf-4860-be29-d2a5b4bfb930 |
| Versions du protocole annoncées | réussi | entrées signées : A ['iu1.0'], B ['iu1.0'] |
| Reconnaissance mutuelle | réussi | chaque box voit l'autre comme box partenaire utilisable |
| Preuves de fraîcheur | réussi | chaque box a la preuve de son annuaire et celle du partenaire ; fraicheur-verifier : preuves conformes |
| Lecture réservée des annuaires | réussi | listes sans compte : [401], entrée d'une box : [200], compte de lecture : [200] ; box, relais et annuaire partenaire lisent par lecture signée ou compte |
| Appairage entre organisations | réussi | partenaires vus par B : ['box-a'] |
| Examen A-vers-B : A → B | réussi | ACKNOWLEDGED, 5 / 5 instances intactes au PACS B, 34 s |
| Examen B-vers-A : B → A | réussi | ACKNOWLEDGED, 5 / 5 instances intactes au PACS A, 32 s |
| Configuration DICOM centrale : routes et plusieurs PACS | réussi | ACKNOWLEDGED ; série THORAX au PACS B (3/3), série CRANE au PACS CRANE (3/3), 0 mal routée(s) ; console de B : 6 ligne(s), dont « Nœud pacs-crane (PACS_B_CRANE, 127.0.0.1:11125) : destination » |
| Configuration DICOM centrale : source non déclarée refusée | réussi | association de « INCONNU » refusée par la box A |
| Refus : box locale hors du périmètre | réussi | Partenaire « box-b » : Cette box n'est pas dans les groupes autorisés à échanger avec « bourgogne-fc » |
| Refus : relais du partenaire non autorisé | réussi | Partenaire « box-b » : Box « Box B » sur un relais non autorisé par l'accord avec « bourgogne-fc » |
| Refus : accord à sens unique | réussi | dépôt refusé par le relais de B, signalé par la box A, message gardé |
| Refus : identifiant de box revendiqué par l'autre organisation | réussi | bourgogne-fc (PNQF-YAD5-OTPT-S5GJ-5YLX-KES7) écartée, tenu par cette organisation |
| Refus : annuaire du partenaire figé | réussi | Partenaire « box-b » : Annuaire de « bourgogne-fc » sans preuve de fraîcheur récente (dernière : 2026-11-17T16:13:30.144071410Z) : échanges arrêtés jusqu'à sa prochaine lecture |
| Refus : accord révoqué | réussi | Partenaire « box-b » : Accord avec « bourgogne-fc » révoqué |
