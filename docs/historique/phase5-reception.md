# Phase 5 — Réception et restitution

> Objectif : chez le destinataire, recevoir les paquets d'un partenaire, vérifier qu'ils sont complets et intacts, et restituer l'étude au PACS local.
> Statut : **fait**, et **essayé en réel de bout en bout** : un examen part d'un PACS, traverse les relais SimpleX publics et arrive identique dans l'autre PACS (§ 5).

## 1. Déroulé chez la box réceptrice

```
fichier proposé ──téléchargement──► fichier reçu ─┐
annonce reçue ────────────────────────────────────┴─► tous les paquets là ? ──► vérifications ──► C-STORE vers le PACS local
```

Les fichiers et l'annonce arrivent **dans n'importe quel ordre** ; la restitution est tentée à chaque arrivée. En pratique, le téléchargement commence avant l'annonce : SimpleX propose le fichier dès son dépôt.

| Étape | Contrôles |
|---|---|
| Fichier proposé | Partenaire **appairé** ; nom conforme `<groupe UUID>-<i>-of-<n>.tar.zst` ; taille non nulle et au plus `max-inbound-file-size` (2 Go). Sinon : ignoré, jamais téléchargé. |
| Annonce reçue | Partenaire appairé ; identifiants valides ; paquets numérotés de 1 à n sans doublon, noms cohérents avec le groupe, empreintes SHA-256 bien formées. Sinon : ignorée. Une annonce déjà reçue est ignorée. |
| Tous les paquets là | Chaque paquet a **la taille et l'empreinte SHA-256 annoncées**. |
| Dépaquetage | Contrôles de la phase 3 (`PackageReader`), puis **manifeste conforme à l'annonce** (groupe, rang, nombre de paquets, étude) et **étude complète** (nombre d'instances du manifeste). |
| Validation DICOM | Chaque instance est lue **de bout en bout** (fichier tronqué ou mal formé refusé) ; UID valides ; étude, série, instance et classe SOP **identiques au manifeste et cohérentes entre méta-informations et jeu de données**. **Une seule instance invalide et rien n'est envoyé.** |
| Restitution | C-STORE vers le PACS local, dans une seule association, chaque instance dans **sa syntaxe de transfert d'origine** (aucune conversion), lue en flux depuis le fichier. Les avertissements du PACS (`Bxxx`) sont acceptés, tout autre statut fait échouer la restitution. |
| Après restitution | Paquets reçus et dossier de travail supprimés ; transfert `RESTORED`. |

Toute anomalie fait passer le transfert reçu à `FAILED` avec sa cause ; depuis la [phase 7](phase7-console.md), ses paquets reçus sont alors écartés, pour qu'une relance reparte de paquets neufs.

## 2. Persistance

Migration Flyway `V3__reception.sql` :

| Table | Contenu |
|---|---|
| `inbound_file` | fichier proposé : partenaire, nom, taille, état (`DOWNLOADING`, `DOWNLOADED`, `CONSUMED`, `FAILED`), emplacement |
| `inbound_transfer` | transfert annoncé : partenaire, groupe, étude, nombre de paquets, annonce reçue, état (`ANNOUNCED`, `RESTORED`, `FAILED`), cause |

Au démarrage, les transferts `ANNOUNCED` dont tous les paquets sont arrivés sont restitués.

## 3. Configuration

```yaml
imagunity:
  restore:
    pacs-host: localhost          # PACS local (mini-PACS)
    pacs-port: 4242
    pacs-aet: ORTHANC
    box-aet: IMAGUNITY            # AET de la box auprès du PACS : à y autoriser
    inbox-dir: ./data/inbox
    max-inbound-file-size: 2GB
    max-unpacked-size: 4GB        # protection contre les bombes de décompression
```

Dans le [Compose](../../deploy/banc-essai/docker-compose.yml), la box A restitue à Orthanc A (`IMAGUNITY_A` → `ORTHANC_A`), la box B à Orthanc B (`IMAGUNITY_B` → `ORTHANC_B`).

## 4. Tests

| Test | Ce qu'il vérifie |
|---|---|
| `ReceiveOrchestratorTest` (19 tests) | Fichiers puis annonce, annonce puis fichiers, plusieurs paquets ; partenaire non appairé ; noms et tailles non conformes ; offre en double ; téléchargement refusé ou en échec ; empreinte différente de l'annonce ; manifeste différent de l'annonce (paquet renommé) ; étude incomplète ; PACS en échec ; 12 formes d'annonces invalides ; annonce en double ; reprise au démarrage. |
| `DicomRestorerTest` (12 tests) | Restitution sans altération vers un PACS de test (le SCP de la box) ; refus total si une instance est invalide ; étude ou série différente du manifeste ; méta-informations incohérentes ; fichiers illisibles, tronqués, sans méta-informations ; association refusée ; PACS injoignable ; instances refusées par le PACS ; limite de contextes de présentation ; interruption. |
| `JdbcInboundRepositoryTest` | Requêtes SQL sur H2 avec les vraies migrations. |

## 5. Essai réel de bout en bout (2026-11-11)

Poste de développement, sans Docker : deux box (jar), deux clients `simplex-chat` v7.1.2 sur les **relais publics de SimpleX**, un PACS d'essai pour la box B (serveur DICOM minimal dcm4che qui range ce qu'il reçoit). **Données synthétiques uniquement.**

| Heure | Box | Événement |
|---|---|---|
| 16:02:08.8 | A | Étude complète reçue du « PACS A » : 6 instances, 2 séries, 52 368 octets |
| 16:02:17.9 | A | Empaquetée : 1 paquet, 50 822 octets (dont environ 9 s de chargement initial de zstd) |
| 16:02:18.1 | B | Téléchargement du paquet commencé (avant l'annonce) |
| 16:02:19.8 | A | Paquet déposé, transfert annoncé à `box-b` |
| 16:02:20.0 | B | Annonce reçue |
| 16:02:24.2 | B | Étude restituée au PACS B : 6 instances (`IMAGUNITY_B` → `ORTHANC_B`) |

**Vérification indépendante** dans le PACS B : **6 instances sur 6 identiques** à celles envoyées (pixels, identifiants d'étude et de série, nom synthétique). Dossier de réception de B vidé après restitution.

Durée totale, du premier C-STORE à la restitution : **20 s**, dont environ 13 s de chargement initial de la bibliothèque zstd sur les deux box (premier transfert après démarrage).

## 6. Limites et suites

- **Accusé de bout en bout** : fait en [phase 6](phase6-accuse.md).
- **Un seul fil pour l'envoi et la réception** : une restitution longue retarde les envois de la même box. À revoir si la volumétrie l'exige.
- **Paquets d'un transfert en échec** : écartés à l'échec depuis la [phase 7](phase7-console.md).
- **Le PACS local doit accepter l'AET de la box** (`box-aet`) ; Orthanc l'accepte par défaut.
- **Identité du patient** : les études reçues sont des dossiers externes ; aucun rapprochement automatique avec les patients locaux n'est fait par la box (rôle du mini-PACS, cf. note d'architecture).
