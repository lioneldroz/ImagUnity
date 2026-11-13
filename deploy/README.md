# Environnement de test

Environnement Docker Compose de la preuve de concept : deux établissements, deux box, le relais IU-T et l'annuaire de l'organisation, sans aucun chemin réseau direct entre les sites.

```
 site-a                        relais (« Internet »)               site-b
┌──────────────┐   ┌────────────────────────────────────┐   ┌──────────────┐
│ orthanc-a    │   │ relay (+ relay-db)                 │   │ orthanc-b    │
│      │       │   │   ▲      directory (+ directory-db)│   │      ▲       │
│      ▼       │   │   │        ▲                       │   │      │       │
│ box-a        ├───┼───┴────────┴───────────────────────┼───┤ box-b        │
└──────────────┘   └────────────────────────────────────┘   └──────────────┘
```

Les box ne font que des connexions sortantes vers le relais et l'annuaire ([transport IU-T](../docs/specification-protocole.md)) ; le relais ne prend les dépôts que des box enrôlées et actives de l'annuaire.

## Premier démarrage

1. **Clé du responsable** : la créer, puis copier sa clé publique dans `annuaire/organisation.pub.json` ([annuaire signé](../docs/annuaire-signe.md)). Son empreinte, vérifiée hors bande, est exigée par les box et le relais.
2. **Phrases de passe des box** (secrets Docker, hors du volume de données, non versionnés) :

```bash
cd deploy
export IMAGUNITY_DIRECTORY_ORGANIZATION_CODE=<empreinte du responsable>
for b in a b; do head -c 24 /dev/urandom | base64 > annuaire/phrase-box-$b.txt; done
docker compose up -d --build
```

3. **Enrôlement** : chaque box crée son identité, ses clés de chiffrement et réserve l'adresse de sa future file au premier démarrage. Télécharger sa demande d'enrôlement (console, onglet Annuaire), la faire signer par le responsable puis publier l'entrée :

```bash
java -cp … com.imagunity.directory.DirectoryAdmin enroler --cles organisation.json --demande demande-a.json --sortie entree-a.json
java -cp … com.imagunity.directory.DirectoryAdmin publier --annuaire http://localhost:8090 --entree entree-a.json
```

   Une fois l'entrée publiée (et lue par le relais), la box crée sa file d'elle-même.
4. **Appairage** : dans la console de la box A, onglet Annuaire, appairer la box B sous le nom `box-b` (ou `POST /api/v1/directory/boxes/{id}/pair`) ; la box B crée le contact à la réception et la poignée de main signée le lie à la box A de l'annuaire. Faire de même dans l'autre sens si besoin (`box-a`).

Sans accès à GitHub ou à Maven pendant la construction (ou pour aller plus vite), la campagne fournit une variante construite à partir d'artefacts préparés : voir [phase 8](../docs/phase8-campagne.md).

| Service | URL |
|---|---|
| Console box A | http://localhost:8081 (API : `/api/v1/box`) ; compte `operateur` / `essai-synthetique` (test uniquement) |
| Console box B | http://localhost:8082 |
| Orthanc A (émetteur) | http://localhost:8042 |
| Orthanc B (destinataire) | http://localhost:8043 |
| Annuaire | http://localhost:8090 |
| Relais IU-T | http://localhost:8443 (HTTP dans ce labo ; derrière un proxy TLS en production) |

Un examen envoyé depuis Orthanc A vers la destination `imagunity-b` est alors empaqueté, chiffré pour la box B, déposé sur le relais, vérifié, puis restitué à Orthanc B (http://localhost:8043), et inversement depuis Orthanc B vers `imagunity-a`.

## Points d'attention

- Chaque box écoute (console 8080, entrée DICOM 11112) sur son adresse du réseau de son site seulement (`SERVER_ADDRESS`, `IMAGUNITY_DICOM_BIND_ADDRESS` = 172.31.1.10 et 172.31.2.10) : rien n'est joignable depuis le réseau des relais. Le réseau du site est prioritaire (`gw_priority`) pour que la console publiée sur l'hôte y arrive.
- La pile réseau de chaque box est celle d'un petit conteneur `net-x` (comme dans un pod) : la box, et son TPM logiciel dans les essais, peuvent redémarrer sans perdre le réseau.
- L'état du transport de chaque box (file de réception, contacts, transferts en cours) est dans `/data/iu` ; un transfert interrompu par un redémarrage reprend où il en était. Le relais garde ses métadonnées dans PostgreSQL (`relay-db`) et les morceaux sur disque (`relay-data`).
- Les paquets sont chiffrés pour la box destinataire, avec les clés de chiffrement de son entrée d'annuaire ([chiffrement applicatif](../docs/chiffrement-applicatif.md)). Pour mettre à jour un parc existant : d'abord le service d'annuaire, puis le relais, puis les box, puis réémettre chaque entrée qui doit changer à partir d'une nouvelle demande d'enrôlement (`--serie` supérieure).
- La dégradation de la liaison (débit, latence, pertes), le pare-feu et les coupures sont appliqués par la campagne de tests ([phase 8](../docs/phase8-campagne.md), dossier [campagne](campagne/)) avec `tc` et `iptables`.
- **Données de test uniquement** : jeux publics ou anonymisés, jamais de patient réel.

### Bascule d'un parc SimpleX

Jusqu'à l'étape 5 de la [spécification](../docs/specification-protocole.md), les box passaient par simplex-chat et les relais SMP et XFTP. Au premier démarrage d'une box sur IU-T, ses partenaires liés à une box de l'annuaire sont repris sous leur nom (les routes DICOM restent valables), ses contacts jamais vérifiés sont oubliés et sa file est créée ; télécharger alors sa nouvelle demande d'enrôlement, qui porte l'adresse IU, et faire réémettre son entrée (`enroler --serie <n+1>`, puis `publier`). À faire sans transfert en cours.

### Mises à jour signées

Les box démarrent par un lanceur qui vérifie la signature de l'éditeur et refuse les retours en arrière ([mises à jour signées](../docs/mises-a-jour-signees.md)). Pour l'activer, placer la clé publique de l'éditeur dans `mises-a-jour/editeur.pub.json`, signer le jar de la box avant `prepare.sh`, puis ajouter `compose.mises-a-jour.yml` avec l'empreinte vérifiée hors bande :

```bash
java -jar ../backend/release/target/imagunity-release.jar signer --cles editeur.json \
    --artefact ../backend/app/target/imagunity-box.jar --version 0.1.0 --sequence 1
export IMAGUNITY_RELEASE_CODE=<empreinte de l'éditeur>
docker compose -f docker-compose.yml -f campagne/compose.campagne.yml -f compose.mises-a-jour.yml up -d --build
```

Une nouvelle version se dépose dans `/data/mises-a-jour/depot/` de la box (jar et manifeste), puis la box est redémarrée. Sans `compose.mises-a-jour.yml`, la box démarre sans vérification, avec un avertissement.

### Clés protégées par le TPM

`compose.tpm.yml` protège les clés des box par un secret scellé dans le TPM de l'hôte (`/dev/tpmrm0`) ; au premier démarrage, la phrase de passe sert une dernière fois, puis son secret peut être retiré. Sans TPM matériel, `campagne/compose.tpm-logiciel.yml` donne à chaque box un TPM logiciel pour les essais. Voir [clés TPM](../docs/cles-tpm.md).

### Plusieurs relais

Une box peut utiliser plusieurs relais IU-T indépendants ([spécification § 6.7](../docs/specification-protocole.md)) : `IMAGUNITY_IU_RELAYS=http://relay:8443,http://relay-2:8443` (le premier est le principal ; remplace `IMAGUNITY_IU_RELAY`) et `IMAGUNITY_IU_REPLICAS` (copies des morceaux de chaque envoi ; `0`, par défaut, une par relais). La surcouche [`compose.deux-relais.yml`](compose.deux-relais.yml) ajoute un second relais, sa base, et règle les deux box :

```bash
docker compose -f docker-compose.yml -f compose.deux-relais.yml up -d
```

Chaque box crée alors une file sur chaque relais et publie une adresse par relais (`iu`, `iu-2`) : télécharger sa nouvelle demande d'enrôlement, faire réémettre son entrée (`enroler --serie <n+1>`, puis `publier`). Tant que l'entrée n'est pas réémise, les messages ne passent que par le relais principal ; les morceaux sont copiés sur les deux dans tous les cas. Une box qui reçoit des morceaux sur un relais absent de sa configuration ignore ces copies. La bande passante montante est multipliée par le nombre de copies : `IMAGUNITY_IU_REPLICAS=1` garde les files redondantes sans doubler les morceaux.

### Routage par champ DICOM

Sur une destination de routage (par exemple `IU_AUTO`), la box choisit le partenaire d'après le contenu de l'examen (modalité, établissement…), par des règles lues dans l'ordre ; une instance qu'aucune règle ne couvre est refusée au PACS. Exemple d'essai : `campagne/compose.routage.yml`. Voir [routage DICOM](../docs/routage-dicom.md).

### Réception DICOMweb (STOW-RS)

Avec un mot de passe `IMAGUNITY_DICOMWEB_PASSWORD` (compte `pacs` par défaut, distinct de l'opérateur), le PACS peut déposer des instances par HTTP sur `POST /dicomweb/<AET>/studies` (route fixe ou destination de routage). `campagne/compose.routage.yml` configure Orthanc A pour déposer ainsi sur `IU_AUTO`. Voir [STOW-RS](../docs/stow-rs.md).

### Outil d'essai du transport

`IuFileTool` (module `transport`) envoie et reçoit un fichier par le relais (`envoyer`, `recevoir`), ouvre une file et y échange des messages (`file-creer`, `message-envoyer`, `message-relever`), sans box. Chaque box ouvre au plus 4 files (`IMAGUNITY_RELAY_MAX_QUEUES_PER_BOX`).

## Réinitialisation

```bash
docker compose down -v
```
