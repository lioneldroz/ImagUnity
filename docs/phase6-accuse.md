# Phase 6 — Accusé de bout en bout

> Objectif : que l'émetteur sache que l'examen est arrivé **complet et identique** chez le destinataire, et puisse libérer son dossier d'envoi.
> Statut : **fait** (tests unitaires et test de bout en bout à deux box sur faux transport). Essai réel sur les relais SimpleX : à refaire depuis le poste (§ 5).

## 1. Déroulé

```
box A (émetteur)                                         box B (destinataire)
ANNOUNCED ──annonce──────────────────────────────────►  ANNOUNCED ──paquets vérifiés, C-STORE──► RESTORED
    │                                                                    └──── anomalie ────────► FAILED
    │◄────────────── imagunity.transfer.ack (RESTORED ou FAILED) ─────────────────┘
    ├── RESTORED et empreinte conforme ──► ACKNOWLEDGED, dossier outbox/<transfert> supprimé
    └── FAILED, ou accusé incohérent ────► FAILED, paquets conservés
```

L'accusé part **dès que le transfert reçu est terminé** chez B, réussi ou non. Il voyage dans la même conversation SimpleX que l'annonce, dans l'autre sens.

## 2. Message

```json
{"type": "imagunity.transfer.ack", "version": 1, "ack": {
  "transferId": "…",              // identifiant du transfert chez l'émetteur, repris de l'annonce
  "packageGroupId": "…",
  "result": "RESTORED",           // ou "FAILED"
  "instances": 6,                 // instances vérifiées et restituées (0 en cas d'échec)
  "partsDigest": "…",             // SHA-256, cf. ci-dessous
  "reason": null                  // cause de l'échec : texte technique, 500 caractères au plus, sans donnée patient
}}
```

**Empreinte des paquets** (`TransferMessages.partsDigest`) : SHA-256 du texte `<groupe>\n` suivi d'une ligne `<rang>:<nombre>:<taille>:<sha256>\n` par paquet, par rang croissant. B la calcule sur l'annonce qu'il a vérifiée paquet par paquet ; A la recalcule sur les paquets qu'il a déposés. Les deux concordent seulement si B a vérifié exactement ce que A a envoyé.

## 3. Contrôles chez l'émetteur

| Cas | Effet |
|---|---|
| Accusé d'un contact non appairé | ignoré |
| Transfert inconnu, ou destiné à un autre partenaire | ignoré (un partenaire ne peut pas acquitter les transferts d'un autre) |
| Transfert pas à l'état `ANNOUNCED` (déjà acquitté, en échec…) | ignoré (accusé en double, accusé tardif) |
| Groupe ou empreinte des paquets différents | transfert `FAILED` : « Accusé du destinataire incohérent » |
| `FAILED` | transfert `FAILED` : « Échec chez le destinataire : <cause> » ; cause bornée, caractères de contrôle retirés ; **paquets gardés** |
| `RESTORED`, empreinte conforme | transfert `ACKNOWLEDGED`, dossier `outbox/<transfert>` supprimé |

**Authenticité.** Dans la preuve de concept, l'accusé n'a pas de signature applicative : il s'appuie sur la conversation SimpleX, chiffrée et authentifiée de bout en bout, que seul le partenaire appairé peut alimenter, et sur la vérification que l'expéditeur est bien le destinataire du transfert. Les accusés signés (Ed25519 + ML-DSA-65) sont prévus par le protocole IU-T (`file.ack`, [spécification](specification-protocole.md) § 10).

## 4. Fiabilité et reprise

| Situation | Traitement |
|---|---|
| B ne peut pas envoyer l'accusé (relais SMP injoignable) | le transfert reste `RESTORED`/`FAILED` avec `ack_sent = false` ; l'accusé est renvoyé au démarrage suivant |
| B a perdu l'appairage avec A | accusé non envoyé, journalisé ; il repartira si le partenaire est de nouveau appairé sous le même nom |
| A s'arrête entre `ACKNOWLEDGED` et le ménage | au démarrage, le dossier d'envoi des transferts `ACKNOWLEDGED` est supprimé |

Migration Flyway `V4__accuse.sql` : colonnes `restored_instances` et `ack_sent` de `inbound_transfer`.

## 5. Tests

| Test | Ce qu'il vérifie |
|---|---|
| `EndToEndAckTest` | Deux box complètes (quatre orchestrateurs) reliées par un faux transport : étude de 4 instances en plusieurs paquets, restituée en B, `ACKNOWLEDGED` en A, dossier d'envoi supprimé ; PACS B en échec → A `FAILED` avec la cause de B, paquets gardés. |
| `SendOrchestratorTest` (+10) | Accusé réussi, en échec, incohérent (empreinte, groupe) ; d'un contact non appairé ou d'un autre partenaire ; transfert inconnu ; accusé en double ou tardif ; messages qui ne sont pas des accusés ; ménage au démarrage ; nettoyage de la cause. |
| `ReceiveOrchestratorTest` (+5) | Accusé envoyé après restitution (instances, empreinte) et après échec (cause) ; renvoi au démarrage après échec du transport, sans doublon ; partenaire disparu. |
| `TransferMessagesTest`, `InboundTest`, `JdbcInboundRepositoryTest` | Format du message, empreinte des paquets, modèle et requêtes SQL. |

Essai réel : à refaire depuis le poste avec deux clients `simplex-chat`, comme en phase 5. Attendu : le transfert de A passe à `ACKNOWLEDGED` quelques secondes après la restitution en B, et `outbox/<transfert>` disparaît.

## 6. Limites et suites

- **Relance** après un accusé `FAILED` : manuelle, depuis la console ([phase 7](phase7-console.md)) ; pas de relance automatique.
- **Délai de garde** : un transfert `ANNOUNCED` dont l'accusé n'arrive jamais (box B détruite) reste en attente ; la console le signale « accusé en retard » ([phase 7](phase7-console.md)).
- **Signature applicative** des accusés : avec l'identité de box du protocole IU-T ou de l'annuaire signé (pilote).
