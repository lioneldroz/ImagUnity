# Phase 2 — Entrée DICOM

> Objectif : recevoir les examens du PACS local (C-STORE), les associer au bon destinataire, et les regrouper par étude.
> Statut : **fait**, testé de bout en bout avec un client DICOM dcm4che et des jeux de données synthétiques. Reste à essayer avec un vrai Orthanc (environnement Docker).

## 1. Ce que fait la box

Pour le PACS, la box est une **destination DICOM ordinaire** : un SCP C-STORE et C-ECHO sur le port 11112 du réseau local. Aucune configuration particulière côté PACS, hormis la déclaration de la destination.

```
PACS ──C-STORE──► AET appelé IU_TO_B ──► dossier de réception ──► regroupement par étude ──► étude complète
                  (routage)               (fichiers tels que reçus)  (délai d'inactivité)       (vers l'orchestrateur)
```

1. **Contrôle à l'association** : l'AET appelant doit être un émetteur autorisé, et l'AET appelé une destination configurée. Sinon, l'association est refusée (`A-ASSOCIATE-RJ`, motif « calling AE title not recognized » ou « called AE title not recognized »).
2. **Réception d'une instance** :
   - l'instance est écrite telle que reçue, au format DICOM Part 10, dans un fichier temporaire ;
   - les UID sont contrôlés (format DICOM, 64 caractères au plus) ;
   - le SOP Instance UID du jeu de données doit être celui de la commande C-STORE ;
   - l'espace disque libre doit rester au-dessus du seuil configuré ;
   - en cas de refus, le PACS reçoit un statut d'erreur DICOM et le fichier temporaire est supprimé.
3. **Rangement** : `<dossier>/<AET appelé>/<partenaire>/<Study UID>/<Series UID>/<SOP UID>.dcm`, par déplacement atomique (le niveau `<partenaire>` vient du [routage par champ DICOM](../reference/routage-dicom.md)).
4. **Regroupement** : une étude est complète quand plus aucune instance n'arrive pendant le délai d'inactivité (30 s par défaut). Une instance reçue deux fois n'est comptée qu'une fois. La même étude envoyée vers deux destinations donne deux études distinctes.
5. **Reprise** : au démarrage, la box retrouve les instances encore présentes dans le dossier de réception et les regroupe à nouveau ; les réceptions interrompues (`.tmp`) sont supprimées.

L'étude complète est transmise sous forme de `ReceivedStudy` (module `domain`). En attendant l'orchestrateur (phase 4), elle est seulement **journalisée**.

## 2. Configuration

Dans `application.yml` (ou par variables d'environnement, comme dans le [Compose](../../deploy/banc-essai/docker-compose.yml)) :

```yaml
imagunity:
  dicom:
    enabled: true
    port: 11112
    storage-dir: ./data/incoming
    study-idle-timeout: 30s
    min-free-space: 1GB
    allowed-calling-aets: [ORTHANC_A]      # vide : toute association est refusée
    routes:
      - called-aet: IU_TO_B                # destination à déclarer dans le PACS
        destination: box-b                 # partenaire destinataire
```

- Variables d'environnement équivalentes : `IMAGUNITY_DICOM_ALLOWED_CALLING_AETS=ORTHANC_A`, `IMAGUNITY_DICOM_ROUTES_0_CALLED_AET=IU_TO_B`, `IMAGUNITY_DICOM_ROUTES_0_DESTINATION=box-b`.
- Les AET de la configuration sont limités à `A-Z a-z 0-9 _ -` (16 caractères au plus) : ils servent aussi de noms de dossiers.
- Par défaut, la liste des émetteurs autorisés est **vide** : une box mal configurée refuse tout plutôt que d'accepter n'importe qui.
- **Limites** (`imagunity.dicom.limits.*`, pré-audit) : l'AET appelant se déclare librement, il n'authentifie pas le poste. Pour restreindre aussi les adresses, `allowed-sources` (adresses ou réseaux CIDR, par exemple `IMAGUNITY_DICOM_LIMITS_ALLOWED_SOURCES=192.168.10.20,10.1.0.0/16` ; vide : toutes). Autres limites, avec leur valeur par défaut : instance de 4 Go au plus (`max-instance-size`, C-STORE et STOW-RS), 32 associations simultanées (`max-associations`), association fermée après 2 minutes sans échange (`idle-timeout`) ou faute de demande d'association 10 s après la connexion (`request-timeout`).

## 3. Sécurité

| Risque (note d'architecture) | Parade en place |
|---|---|
| Toute machine du réseau local peut pousser vers la box | Liste des AET autorisés, refus par défaut. **Limite** : un AET se déclare, il ne s'authentifie pas. Le filtrage réseau (seul le PACS joint le port 11112) reste nécessaire. |
| Routage vers le mauvais partenaire | Seules les destinations configurées sont acceptées ; une destination inconnue est refusée dès l'association. |
| Chemins forgés via les UID | Les UID sont validés avant de servir de noms de fichiers, et le chemin final est vérifié sous le dossier de réception. |
| Saturation du disque | Seuil d'espace libre minimal ; au-dessous, le PACS reçoit `Out of Resources` (il pourra renvoyer plus tard). |
| Données de santé dans les journaux | Les journaux ne contiennent ni nom de patient ni UID au niveau `INFO` : seulement les AET, le nombre d'instances et de séries, et les tailles. |

## 4. Tests

| Test | Ce qu'il vérifie |
|---|---|
| `DicomStoreScpTest` (8 tests) | C-ECHO ; réception d'une étude de 3 instances sur 2 séries et notification unique ; fichiers Part 10 complets et UID conservés ; refus d'un émetteur inconnu ; refus d'une destination inconnue ; refus d'un UID de commande différent ; refus d'un Study UID forgé (`../../evil`) ; refus quand le disque est plein ; reprise d'une étude après redémarrage. |
| `StudyAssemblerTest` (5 tests) | Délai d'inactivité, doublons, deux destinations, comptage des séries, isolement d'un consommateur en erreur. |
| `DicomInputConfigTest`, `DicomUidsTest` | Règles de configuration et de validation des UID. |
| `DicomInputWiringTest` | Lecture de la configuration Spring et ouverture du port DICOM au démarrage de l'application. |

Les jeux de données sont **synthétiques** (`TestDatasets`) : aucun patient réel.

## 5. Ce qui reste

- **Essai avec un vrai Orthanc** : le Compose déclare `IU_TO_B` dans Orthanc A et `IU_TO_A` dans Orthanc B ; à faire dès que Docker est disponible.
- **Livraison « au moins une fois »** : une étude encore présente dans le dossier de réception est signalée à nouveau après un redémarrage. L'orchestrateur (phase 4) devra la prendre en charge (déplacer ses fichiers) et ignorer les doublons.
- **Routage par champ DICOM** et **réception STOW-RS** (DICOMweb) : prévus après la preuve de concept.
- **Volumétrie** : non mesurée sur de grosses études (plusieurs milliers d'instances, plusieurs Go).
