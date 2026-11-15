# Banc d'essai de la fédération

Deux organisations sur un même poste, sans Docker. Chacune a son service d'annuaire, son relais IU-T et une box, et un PACS simulé de chaque côté (pynetdicom). Données synthétiques uniquement.

| | Organisation A « grand-est » | Organisation B « bourgogne-fc » |
|---|---|---|
| Annuaire | `http://127.0.0.1:8090` | `http://127.0.0.1:8091` |
| Relais | `http://127.0.0.1:8443` | `http://127.0.0.1:8444` |
| Box (console) | `http://127.0.0.1:8081`, groupe `imagerie` | `http://127.0.0.1:8082`, groupe `neuro` |
| PACS simulé | AET `PACS_A`, port 11114 | AET `PACS_B`, port 11115 |

## Lancer

```bash
cd backend && mvn -DskipTests package && cd ..
pip install -r deploy/banc-essai/federation/requirements.txt
python3 deploy/banc-essai/federation/banc.py            # --garder : laisse tout tourner à la fin
```

Le script efface puis remplit `travail/` : clés des responsables, bases H2, journaux de chaque composant (`travail/journaux/`), et le compte rendu `travail/resultats.md`. Il sort en erreur si un essai échoue. Le jar H2 est pris dans le dépôt Maven local, ou dans `H2_JAR`.

## Ce qu'il vérifie

1. **Enrôlement** : chaque responsable tire un code par box, la box envoie sa demande avec la preuve du code, `enroler-attente` la signe.
2. **Reconnaissance mutuelle** : chaque responsable reconnaît l'autre (`reconnaitre`). Le périmètre couvre les groupes des deux box, et les relais autorisés sont ceux de chaque organisation. Chaque box voit alors l'autre comme box partenaire utilisable.
3. **Appairage entre organisations**, par l'annuaire, depuis la console de la box A.
4. **Examens** de A vers B, puis de B vers A : C-STORE du PACS émetteur vers sa box, puis réception au PACS destinataire, instances comparées une à une (pixels) et accusé de réception.
5. **Preuves de fraîcheur** : chaque responsable délègue une clé de fraîcheur à son annuaire (`fraicheur-deleguer`). Chaque box reçoit la preuve de son annuaire et celle du partenaire, relayée, et `fraicheur-verifier` les trouve conformes.
6. **Refus**, chacun avec son motif :
   - box locale hors du périmètre de l'accord ;
   - relais du partenaire non autorisé par l'accord ;
   - accord à sens unique : B révoque, le relais de B refuse les dépôts de A, et la box A le signale dans son journal en gardant le message ;
   - identifiant de box revendiqué par l'autre organisation : B publie une entrée sous l'identifiant de la box A, qui est écartée et signalée dans la console de A ;
   - annuaire du partenaire figé : l'annuaire de B s'arrête, et passé l'âge maximal de sa preuve (90 s sur le banc, 48 h par défaut), la box A refuse d'envoyer vers B ;
   - accord révoqué par A.

## Dernier compte rendu

Voir [resultats.md](resultats.md).
