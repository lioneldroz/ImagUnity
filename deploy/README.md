# Environnement de test

Environnement Docker Compose de la preuve de concept : deux établissements, deux box, deux relais, sans aucun chemin réseau direct entre les sites.

```
 site-a                        relais                         site-b
┌──────────────┐   ┌───────────────────────────────┐   ┌──────────────┐
│ orthanc-a    │   │ smp-server      xftp-server   │   │ orthanc-b    │
│      │       │   │      ▲               ▲        │   │      ▲       │
│      ▼       │   │      │               │        │   │      │       │
│ box-a+chat-a ├───┼──────┴───────────────┴────────┼───┤ box-b+chat-b │
└──────────────┘   └───────────────────────────────┘   └──────────────┘
```

## Démarrage

```bash
cd deploy
docker compose up -d --build
```

Sans accès à GitHub ou à Maven pendant la construction (ou pour aller plus vite), la campagne fournit une variante construite à partir d'artefacts préparés : voir [phase 8](../docs/phase8-campagne.md).

| Service | URL |
|---|---|
| Console box A | http://localhost:8081 (API : `/api/v1/box`) ; compte `operateur` / `essai-synthetique` (test uniquement) |
| Console box B | http://localhost:8082 |
| Orthanc A (émetteur) | http://localhost:8042 |
| Orthanc B (destinataire) | http://localhost:8043 |

## Points d'attention

- Les relais génèrent leurs clés et leur empreinte au premier démarrage. Les clients `simplex-chat` lisent ces empreintes dans les volumes de configuration des relais (voir [simplex-chat/entrypoint.sh](simplex-chat/entrypoint.sh)).
- Chaque box est composée de deux conteneurs qui partagent la même pile réseau (celle d'un petit conteneur `net-x`, comme dans un pod : chacun peut redémarrer seul) **et le même volume `/data`** : `chat-x` (client SimpleX non modifié, AGPL-3.0) et `box-x` (logiciel ImagUnity, qui pilote le client par WebSocket sur `localhost:5225`). Le client lit les paquets à envoyer et écrit les paquets reçus aux chemins donnés par la box ; son dossier temporaire (`--temp-folder /data/tmp`) doit être sur le même système de fichiers que `/data/inbox`, sinon le fichier reçu ne peut pas y être renommé.
- Chaque box écoute (console 8080, entrée DICOM 11112) sur son adresse du réseau de son site seulement (`SERVER_ADDRESS`, `IMAGUNITY_DICOM_BIND_ADDRESS` = 172.31.1.10 et 172.31.2.10) : rien n'est joignable depuis le réseau des relais. Le réseau du site est prioritaire (`gw_priority`) pour que la console publiée sur l'hôte y arrive.
- Les relais ont une adresse IPv4 fixe sur le réseau `relais` (172.30.0.10 et .11) : leurs images refusent un nom court et, avec un nom de domaine, exigent un certificat HTTPS pour leur site web, désactivé ici (`WEB_MANUAL`).
- Orthanc A déclare la destination DICOM `IU_TO_B` : envoyer un examen à cette destination désigne la box B comme destinataire. L'entrée DICOM de la box arrive en phase 2.
- La dégradation de la liaison (débit, latence, pertes), le pare-feu et les coupures sont appliqués par la campagne de tests ([phase 8](../docs/phase8-campagne.md), dossier [campagne](campagne/)) avec `tc netem` et `iptables`.
- **Données de test uniquement** : jeux publics ou anonymisés, jamais de patient réel.

## Appairer les deux box

Après le démarrage, appairer A et B (voir [phase 4](../docs/phase4-orchestrateur.md#2-appairage-sans-annuaire)) :

```bash
H='X-ImagUnity-Console: 1'; U='operateur:essai-synthetique'   # compte de l'environnement de test
curl -s -u "$U" -H "$H" -X POST http://localhost:8081/api/v1/peers/invitations -H 'Content-Type: application/json' -d '{"name":"box-b"}'
curl -s -u "$U" -H "$H" -X POST http://localhost:8082/api/v1/peers/connections -H 'Content-Type: application/json' -d '{"name":"box-a","link":"<lien renvoyé par la box A>"}'
```

Un examen envoyé depuis Orthanc A vers la destination `imagunity-b` est alors empaqueté, transmis à la box B, vérifié, puis restitué à Orthanc B (http://localhost:8043), et inversement depuis Orthanc B vers `imagunity-a`.

## Annuaire de l'organisation (facultatif)

Le profil `annuaire` ajoute le service d'annuaire (port 8090) et sa base PostgreSQL. Il faut d'abord créer la clé du responsable et copier sa clé publique dans `annuaire/organisation.pub.json` (voir [annuaire signé](../docs/annuaire-signe.md)) :

```bash
docker compose --profile annuaire up -d directory
```

Pour que les box utilisent l'annuaire (enrôlement, liaison des partenaires, refus des box révoquées), ajouter `compose.annuaire.yml` avec l'empreinte du responsable vérifiée hors bande :

```bash
export IMAGUNITY_DIRECTORY_ORGANIZATION_CODE=<empreinte>
for b in a b; do head -c 24 /dev/urandom | base64 > annuaire/phrase-box-$b.txt; done   # chiffrent les clés des box
docker compose -f docker-compose.yml -f compose.annuaire.yml --profile annuaire up -d
```

Les paquets sont alors chiffrés pour la box destinataire, avec les clés de chiffrement de son entrée d'annuaire ([chiffrement applicatif](../docs/chiffrement-applicatif.md)). Pour mettre à jour un parc existant : d'abord le service d'annuaire, puis les box, puis réémettre chaque entrée à partir d'une nouvelle demande d'enrôlement (`--serie` supérieure).

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

`compose.tpm.yml` (après `compose.annuaire.yml`) protège les clés des box par un secret scellé dans le TPM de l'hôte (`/dev/tpmrm0`) ; au premier démarrage, la phrase de passe sert une dernière fois, puis son secret peut être retiré. Sans TPM matériel, `campagne/compose.tpm-logiciel.yml` donne à chaque box un TPM logiciel pour les essais. Voir [clés TPM](../docs/cles-tpm.md).

### Routage par champ DICOM

Sur une destination de routage (par exemple `IU_AUTO`), la box choisit le partenaire d'après le contenu de l'examen (modalité, établissement…), par des règles lues dans l'ordre ; une instance qu'aucune règle ne couvre est refusée au PACS. Exemple d'essai : `campagne/compose.routage.yml`. Voir [routage DICOM](../docs/routage-dicom.md).

## Réinitialisation

```bash
docker compose down -v
```
