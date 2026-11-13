# Phase 4 — Orchestrateur

> Objectif : relier l'entrée DICOM, l'empaquetage et le transport, avec un état de transfert enregistré qui survit aux coupures.
> Statut : **fait côté émetteur**, testé avec un faux transport, dans l'application complète, et **en réel entre deux box** reliées à deux clients `simplex-chat` sur les relais publics (§ 6).

## 1. Machine à états d'un transfert

```
RECEIVED ──empaquetage──► PACKAGED ──dépôt de tous les paquets──► UPLOADED ──annonce──► ANNOUNCED ─ ─► ACKNOWLEDGED (phase 6)
    └───────────────────────────────── échec ─────────────────────────────────► FAILED
```

| Étape | Ce que fait l'orchestrateur | Enregistré |
|---|---|---|
| Étude complète reçue | Crée le transfert `RECEIVED` (ou reprend celui qui existe pour la même étude et la même destination) | transfert |
| Empaquetage | `StudyPackager` produit les paquets dans `outbox/<transfert>/` ; les fichiers de réception sont alors supprimés | paquets, `PACKAGED` |
| Dépôt | Chaque paquet est confié au transport (`sendFile`) vers le partenaire de la destination | identifiant de fichier par paquet |
| Fin du dépôt | À chaque `FileSent`, le paquet est marqué déposé ; quand tous le sont : `UPLOADED` | paquet déposé |
| Annonce | Message `imagunity.transfer.announce` au partenaire : transfert, groupe de paquets, étude, nom / taille / SHA-256 de chaque paquet | `ANNOUNCED` |
| Échec | Partenaire non appairé, empaquetage impossible, transport en erreur (`FileFailed`, envoi refusé) | `FAILED` + cause |

Toutes les étapes s'exécutent sur **un seul fil** (« orchestrateur ») : pas de verrou, et les événements du transport sont traités dans l'ordre.

### Reprise après redémarrage

| État au redémarrage | Reprise |
|---|---|
| `RECEIVED` | Les fichiers sont encore dans le dossier de réception : l'entrée DICOM signale à nouveau l'étude, l'orchestrateur reprend le même transfert. |
| `PACKAGED` | Les paquets pas encore confiés au transport le sont ; ceux déjà confiés sont poursuivis par le client SimpleX (à vérifier en phase 8). |
| `UPLOADED` | L'annonce est envoyée. |
| `ANNOUNCED` | En attente de l'accusé (phase 6). |

L'orchestrateur démarre **avant** l'entrée DICOM, pour que les études reprises trouvent preneur.

## 2. Appairage (sans annuaire)

Chaque box nomme **elle-même** son partenaire : c'est ce nom qu'utilisent les routes DICOM (`destination`). Le nom de profil annoncé par le partenaire n'est conservé qu'à titre d'information : un partenaire ne peut pas se faire passer pour un autre en changeant son nom.

```bash
# Box A : invitation pour le partenaire qu'elle appellera « box-b »
curl -s -X POST http://localhost:8081/api/v1/peers/invitations \
     -H 'Content-Type: application/json' -d '{"name":"box-b"}'
# → {"name":"box-b","link":"https://simplex.chat/invitation#..."}

# Le lien est transmis hors bande (QR code, messagerie de confiance), puis box B le rejoint :
curl -s -X POST http://localhost:8082/api/v1/peers/connections \
     -H 'Content-Type: application/json' -d '{"name":"box-a","link":"<lien>"}'

# Vérification, de chaque côté :
curl -s http://localhost:8081/api/v1/peers
```

- Un seul appairage peut être en cours à la fois ; il expire après `imagunity.orchestration.pairing-timeout` (1 h).
- Une connexion qui arrive sans appairage en cours est ignorée.
- Un ré-appairage remplace l'ancien contact du même nom.

## 3. Persistance

Base H2 de la box, migration Flyway `V2__orchestration.sql` :

| Table | Contenu |
|---|---|
| `transfer` | état, destination, groupe de paquets, nombre de paquets, cause d'échec, horodatages |
| `transfer_part` | chaque paquet : fichier, taille, SHA-256, identifiant de fichier du transport, déposé ou non |
| `peer` | nom local du partenaire, identifiant du contact SimpleX, nom de profil annoncé, date d'appairage |

## 4. Configuration

```yaml
imagunity:
  orchestration:
    outbox-dir: ./data/outbox
    pairing-timeout: 1h
    max-part-size: 900MB
    compression-level: 3
  transport:
    simplex:
      enabled: true                 # false : transport désactivé, les transferts échouent (développement)
      url: ws://localhost:5225      # API du client simplex-chat, même machine
      startup-timeout: 2m           # la box attend que simplex-chat soit prêt
      command-timeout: 60s
```

## 5. Tests

| Test | Ce qu'il vérifie |
|---|---|
| `SendOrchestratorTest` (18 tests) | Parcours complet jusqu'à l'annonce ; transfert en plusieurs paquets annoncé seulement à la fin ; partenaire non appairé ; échecs d'empaquetage, de dépôt, d'annonce et signalés par le transport ; événements en double ou inconnus ; reprise des états `PACKAGED` et `UPLOADED` ; étude signalée à nouveau ; nettoyage des dossiers de réception ; isolement d'une étape en erreur. |
| `PairingServiceTest` (9 tests) | Nom choisi localement, connexion inattendue ignorée, expiration, ré-appairage, échec d'acceptation. |
| `JdbcTransferRepositoryTest`, `JdbcPeerRepositoryTest` | Requêtes SQL sur H2 avec les vraies migrations. |
| `OrchestrationWiringTest` | Dans l'application complète : étude reçue → transfert empaqueté → envoi refusé par le transport désactivé ; API d'appairage en HTTP (200, 400, 502) ; ordre de démarrage. |

Défauts trouvés par les tests et corrigés : un identifiant de fichier absent relu comme `0` en base ; le dossier d'une étude à plusieurs séries non supprimé (ordre de suppression).

## 6. Essai réel (2026-11-11)

Deux box lancées depuis le jar sur le poste de développement, chacune reliée à son client `simplex-chat` v7.1.2 (relais publics de SimpleX, données synthétiques uniquement), sans Docker :

| Étape | Résultat |
|---|---|
| Appairage par l'API (`invitations` sur A, `connections` sur B) | Les deux box enregistrent leur partenaire en environ 1 s, sous le nom choisi localement (`box-b`, `box-a`). |
| Envoi de 6 instances CT synthétiques (2 séries) vers A, destination `IU_TO_B` | 6 C-STORE acceptés (statut 0). |
| Étude complète (délai d'inactivité réglé à 3 s) | 6 instances, 2 séries, 52 368 octets. |
| Empaquetage | 1 paquet, 50 819 octets, en 2 s ; dossier de réception vidé. |
| Dépôt XFTP et annonce SMP | Transfert `ANNOUNCED` 4 s après la fin de l'étude. |

**Observation qui confirme un choix de conception** : SimpleX a renommé localement les profils annoncés en `box-b_3` et `box-a_3` (contacts des essais précédents). Si le routage reposait sur le nom de profil, il aurait cassé ; le nom choisi à l'appairage, lui, est stable.

## 7. Limites et suites

- **API d'appairage sans authentification** : toute machine qui joint le port HTTP de la box peut lancer un appairage. Acceptable sur l'environnement de test ; la console et son contrôle d'accès arrivent en phase 7, l'annuaire signé au pilote.
- **Côté destinataire** : les fichiers et l'annonce arrivent chez la box B, mais elle ne les traite pas encore (phase 5 : réception, vérification, restitution au PACS).
- **Relance d'un transfert en échec** : prévue avec la console (phase 7). La machine à états l'autorise déjà (`FAILED → RECEIVED`, `PACKAGED`, …).
- **Premier empaquetage lent sur Windows** (environ 10 s) : chargement initial de la bibliothèque native zstd, probablement ralenti par l'antivirus. Sans effet sur les suivants.
- **Nettoyage du dossier d'envoi** après accusé de réception : phase 6.
