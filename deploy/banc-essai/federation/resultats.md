# Banc fédération — 2026-11-18 13:42

| Essai | Résultat | Détail |
|---|---|---|
| Machines séparées | réussi | fichiers déposés chez A : annuaire/config/fraicheur/delegation.json, annuaire/config/fraicheur/fraicheur.json, annuaire/config/organisation.pub.json, box/config/organisation.pub.json, relais/config/organisation.pub.json ; clé privée et codes seulement sur le poste du responsable |
| Enrôlement des deux box | réussi | A = 779f3a04-852f-4ab0-894f-f9555511c2c7, B = 61888ece-256c-4560-adaa-3f608e41fa7d |
| Versions du protocole annoncées | réussi | entrées signées : A ['iu1.0'], B ['iu1.0'] |
| Reconnaissance mutuelle | réussi | chaque box voit l'autre comme box partenaire utilisable |
| Preuves de fraîcheur | réussi | chaque box a la preuve de son annuaire et celle du partenaire ; fraicheur-verifier : preuves conformes |
| Lecture réservée des annuaires | réussi | listes sans compte : [401], entrée d'une box : [200], compte de lecture : [200] ; box, relais et annuaire partenaire lisent par lecture signée ou compte |
| Appairage entre organisations | réussi | partenaires vus par B : ['box-a'] |
| Examen A-vers-B : A → B | réussi | ACKNOWLEDGED, 5 / 5 instances intactes au PACS B, 34 s |
| Examen B-vers-A : B → A | réussi | ACKNOWLEDGED, 5 / 5 instances intactes au PACS A, 31 s |
| Configuration DICOM centrale : routes et plusieurs PACS | réussi | ACKNOWLEDGED ; série THORAX au PACS B (3/3), série CRANE au PACS CRANE (3/3), 0 mal routée(s) ; console de B : 6 ligne(s), dont « Nœud pacs-crane (PACS_B_CRANE, 127.0.0.1:11125) : destination — C-ECHO réussi le 18/11 13:37 » |
| Configuration DICOM centrale : source non déclarée refusée | réussi | association de « INCONNU » refusée par la box A |
| Tester maintenant : réveil de la box par l'annuaire, C-ECHO, compte rendu | réussi | réponses en 1.1, 1.0, 1.1 s (signature et dépôt compris) ; 3 C-ECHO reçus par PACS_A ; pacs-a : réussi ; flux ouvert : True |
| Refus : box locale hors du périmètre | réussi | Partenaire « box-b » : Cette box n'est pas dans les groupes autorisés à échanger avec « bourgogne-fc » |
| Refus : relais du partenaire non autorisé | réussi | Partenaire « box-b » : Box « Box B » sur un relais non autorisé par l'accord avec « bourgogne-fc » |
| Refus : accord à sens unique | réussi | dépôt refusé par le relais de B, signalé par la box A, message gardé |
| Refus : identifiant de box revendiqué par l'autre organisation | réussi | bourgogne-fc (NSDN-RICZ-BWNC-ZJ3O-QMPS-6JKU) écartée, tenu par cette organisation |
| Refus : annuaire du partenaire figé | réussi | Partenaire « box-b » : Annuaire de « bourgogne-fc » sans preuve de fraîcheur récente (dernière : 2026-11-18T13:39:50.421627390Z) : échanges arrêtés jusqu'à sa prochaine lecture |
| Refus : accord révoqué | réussi | Partenaire « box-b » : Accord avec « bourgogne-fc » révoqué |
