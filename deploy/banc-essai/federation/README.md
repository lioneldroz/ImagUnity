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

Le script efface puis remplit `travail/`, une **machine simulée par dossier** :

```
travail/
  A/, B/                  une organisation chacune
    poste-responsable/    organisation.json (clé privée), organisation.json.pub.json, codes.json,
                          fraicheur.json, delegation-fraicheur.json, partenaire-<id>.pub.json
    annuaire/             config/organisation.pub.json, config/fraicheur/ ; donnees/ (base H2)
    relais/               config/organisation.pub.json ; donnees/, morceaux/
    box/                  config/organisation.pub.json ; donnees/ (identité, base, transferts)
    pacs/                 instances reçues par le PACS simulé
  journaux/               un journal par composant
  resultats.md            le compte rendu
```

Chaque composant ne lit que ses propres fichiers. Le banc les y **dépose** depuis le poste du responsable, comme l'exploitant et l'installateur : la clé publique à chaque machine, la clé de fraîcheur à l'annuaire seulement. La clé publique du partenaire est **remise** d'un responsable à l'autre, sur son poste. Le premier essai vérifie qu'aucune machine n'a reçu de clé privée, de code d'enrôlement ou de clé de partenaire. L'outil du responsable s'exécute dans `poste-responsable/`. Il sort en erreur si un essai échoue. Le jar H2 est pris dans le dépôt Maven local, ou dans `H2_JAR`.

## Ce qu'il vérifie

0. **Machines séparées** : fichiers déposés sur chaque machine, rien de secret hors du poste du responsable.
1. **Enrôlement** : chaque responsable tire un code par box, la box envoie sa demande avec la preuve du code, `enroler-attente` la signe.
   L'entrée signée de chaque box annonce les versions du protocole de sa demande (`"protocols": ["iu1.0"]`).
2. **Reconnaissance mutuelle** : chaque responsable reconnaît l'autre (`reconnaitre`). Le périmètre couvre les groupes des deux box, et les relais autorisés sont ceux de chaque organisation. Chaque box voit alors l'autre comme box partenaire utilisable.
3. **Appairage entre organisations**, par l'annuaire, depuis la console de la box A.
4. **Examens** de A vers B, puis de B vers A : C-STORE du PACS émetteur vers sa box, puis réception au PACS destinataire, instances comparées une à une (pixels) et accusé de réception.
5. **Preuves de fraîcheur** : chaque responsable délègue une clé de fraîcheur à son annuaire (`fraicheur-deleguer`). Chaque box reçoit la preuve de son annuaire et celle du partenaire, relayée, et `fraicheur-verifier` les trouve conformes.
6. **Lecture réservée** : les deux annuaires tournent avec `IMAGUNITY_DIRECTORY_RESTRICTED_READ=true`. Leurs listes sont refusées sans compte ni signature (401), l'entrée d'une box reste publique, et le compte de lecture du relais passe. Tout le reste du banc le vérifie de bout en bout : les box lisent par lectures signées (d'abord leur seule entrée tant qu'elles ne sont pas enrôlées), les relais avec leur compte, et chaque annuaire lit celui du partenaire en signant avec sa clé de fraîcheur. Toutes ces lectures se font par différence.
7. **Configuration DICOM centrale** : chaque responsable signe et publie la configuration de sa box (`configuration-signer`, `configuration-publier`), comme l'onglet « Configuration des box » de l'interface. A déclare son PACS comme nœud source et le route vers la box de B. B restitue les séries `*CRANE*` venues de A à un second PACS simulé, le reste à son PACS. Une étude de deux séries arrive série par série au bon PACS, accusé compris. Une source non déclarée est refusée dès l'association.
8. **Refus**, chacun avec son motif :
   - box locale hors du périmètre de l'accord ;
   - relais du partenaire non autorisé par l'accord ;
   - accord à sens unique : B révoque, le relais de B refuse les dépôts de A, et la box A le signale dans son journal en gardant le message ;
   - identifiant de box revendiqué par l'autre organisation : B publie une entrée sous l'identifiant de la box A, qui est écartée et signalée dans la console de A ;
   - annuaire du partenaire figé : l'annuaire de B s'arrête, et passé l'âge maximal de sa preuve (90 s sur le banc, 48 h par défaut), la box A refuse d'envoyer vers B ;
   - accord révoqué par A.

## Dernier compte rendu

Voir [resultats.md](resultats.md).
