# Annuaire signé

> Objectif (pilote, priorité 1 du [plan](plan-de-realisation.md)) : savoir **qui est qui** entre les box d'une organisation. Chaque box est enrôlée par le responsable de l'organisation, qui signe son entrée ; les box vérifient ces signatures avant d'échanger et refusent une box révoquée. Conforme au § 9 de la [spécification IU-T](specification-protocole.md), et utilisable dès maintenant avec le transport SimpleX.
> Statut : **étape A faite** (socle cryptographique et outil du responsable). Étapes B et C à venir.

## 1. Principes

```
           Responsable de l'organisation (clé hors ligne)
                 │ signe les entrées (enrôlement, révocation)
                 ▼
  Box A ───lit───► Annuaire (publie les entrées signées) ◄───lit─── Box B
    │                                                               │
    └── vérifie la signature avec la clé du responsable, épinglée ──┘
        à l'installation après vérification hors bande de son empreinte
```

- **L'annuaire n'est pas de confiance.** Il ne fait que publier des entrées signées par le responsable. Un annuaire compromis ne peut ni créer une box, ni changer ses clés. Il ne peut que retenir des entrées (déni de service), et il ne peut pas faire revenir une entrée remplacée (§ 3).
- **La clé du responsable** est la racine de confiance. Elle reste hors ligne, chez le responsable. Chaque box l'**épingle** à l'installation, après avoir vérifié son empreinte courte par un autre canal (téléphone, papier, QR code).
- **Chaque box a sa propre identité** : une paire de clés de signature générée dans la box, et qui ne la quitte jamais (TPM ou élément sécurisé pour le pilote).
- **Cryptographie** : uniquement des primitives standard du JDK 25. La suite `IU1` signe en double avec Ed25519 (RFC 8032) et ML-DSA-65 (FIPS 204, résistant au calcul quantique), et les deux signatures doivent être valides.

## 2. Formats

### 2.1 Document signé

Toute pièce signée (demande d'enrôlement, entrée de box) a la même enveloppe :

```json
{
  "payload": "{\"suite\":\"IU1\",\"boxId\":\"…\", …}",
  "signatures": { "suite": "IU1", "ed25519": "<base64>", "mlDsa65": "<base64>" }
}
```

La signature porte sur les **octets UTF-8 exacts** du texte `payload`. Celui-ci n'est relu qu'après vérification. Ce choix évite toute canonicalisation du JSON, source classique d'erreurs.

### 2.2 Entrée de box

```json
{
  "suite": "IU1",
  "organization": "chu-exemple",
  "boxId": "3f6c…",
  "displayName": "CHU Exemple — imagerie",
  "signingKeys": { "ed25519": "<X.509 base64>", "mlDsa65": "<X.509 base64>" },
  "transport": { "simplex": "<adresse de contact SimpleX>" },
  "serial": 1,
  "validFrom": "2026-11-12T00:00:00Z",
  "validUntil": "2027-11-12T00:00:00Z",
  "revoked": false, "revokedAt": null, "revocationReason": null
}
```

- `serial` augmente à chaque nouvelle émission pour la même box (renouvellement, changement d'adresse, révocation) : seule l'entrée de numéro le plus haut compte.
- La **révocation** est une entrée signée de numéro supérieur, avec `revoked: true`.

### 2.3 Demande d'enrôlement

La box produit une demande signée **par ses propres clés** : identifiant, nom proposé, clés publiques, adresse de transport, date. Cette signature prouve qu'elle détient les clés privées correspondantes. Le responsable vérifie cette preuve, puis confirme hors bande l'empreinte de la box avec son installateur avant de signer l'entrée.

### 2.4 Empreinte courte

L'empreinte d'une clé hybride est le SHA-256 de `IU1`, puis des deux clés publiques. Pour la lire au téléphone, ses 120 premiers bits sont écrits en base 32 par groupes de quatre, par exemple `K7QF-2MZD-XA4N-…` (6 groupes).

## 3. Règles de vérification (côté box)

Une entrée n'est retenue que si :

1. ses deux signatures sont valides avec la clé épinglée du responsable ;
2. sa suite est `IU1` et son organisation est celle de la box ;
3. son numéro n'est pas inférieur au plus haut déjà vu pour cette box. Sinon, c'est un **retour en arrière**, qui est refusé et signalé : un annuaire compromis pourrait sinon resservir l'entrée d'avant une révocation ;
4. et, pour l'échanger avec une box, la date courante est dans `[validFrom, validUntil)` et l'entrée n'est pas révoquée.

Le résultat est l'un des états suivants : `ACTIVE`, `REVOKED`, `EXPIRED` ou `NOT_YET_VALID`. Une signature invalide, une autre organisation ou un retour en arrière sont des **erreurs de sécurité**, pas des états.

## 4. Outil du responsable (étape A)

L'outil est en ligne de commande, et le responsable l'exécute sur un poste qui garde sa clé hors ligne :

| Commande | Effet |
|---|---|
| `organisation-creer --organisation <id> --cles <fichier>` | Génère la clé du responsable (fichier privé, droits 600) et sa clé publique `<fichier>.pub.json` ; affiche l'empreinte courte |
| `empreinte --cle-publique <fichier>` | Affiche l'empreinte courte d'une clé publique d'organisation |
| `box-demande --cles <fichier> --box-id <uuid> --nom <nom> [--simplex <adresse>] --sortie <fichier>` | Côté box, en attendant l'étape C : génère l'identité de la box si besoin et produit une demande signée |
| `enroler --cles <clé organisation> --demande <fichier> [--validite-jours 365] [--serie 1] --sortie <fichier>` | Vérifie la preuve de possession, affiche l'empreinte de la box à confirmer hors bande, puis signe l'entrée |
| `revoquer --cles <clé organisation> --entree <fichier> --motif <texte> --sortie <fichier>` | Émet l'entrée révoquée (numéro + 1) |
| `verifier --cle-publique <fichier> --entree <fichier>` | Vérifie une entrée et affiche son état |

Lancement, en attendant un paquet dédié (étape B) :

```bash
cd backend && ./mvnw -q -pl directory package dependency:build-classpath -Dmdep.outputFile=cp.txt
LANG=C.UTF-8 java -cp directory/target/classes:$(cat cp.txt) com.imagunity.directory.DirectoryAdmin <commande> …
```

Le terminal doit être en UTF-8 (`LANG=C.UTF-8` ou équivalent). Sinon, la JVM décode mal les arguments accentués, par exemple un nom de box avec « — ».

Exemple : l'organisation reçoit l'empreinte `TVF3-WIAA-CQ6V-MZGC-HKTQ-5WEH`, et la box `FI55-3QYO-MVFX-OFXI-CNEK-NQ77`. Après révocation, `verifier` affiche `REVOKED` et sort avec le code 1. Une entrée pèse environ 7,5 Ko, à cause de la clé et de la signature ML-DSA-65.

Les clés privées sont stockées en PKCS#8 dans un fichier JSON, en droits 600. Pour le pilote, il faudra un chiffrement par phrase de passe ou un support matériel (carte à puce, HSM) pour la clé du responsable, et un TPM pour celle de la box.

## 5. Étapes suivantes

- **B. Service d'annuaire** (Spring Boot, PostgreSQL) :
  - publication des entrées signées (`GET /directory/v1/entries`, et par box) ;
  - dépôt par le responsable, authentifié ; le service refuse une entrée mal signée ou un retour en arrière, mais les box revérifient tout ;
  - historique conservé.
- **C. Intégration dans la box** :
  - identité générée au premier démarrage ;
  - demande d'enrôlement depuis la console ;
  - clé du responsable épinglée par configuration, avec vérification de son empreinte ;
  - synchronisation périodique de l'annuaire ;
  - appairage par l'annuaire : connexion à l'adresse SimpleX publiée, puis message `hello` signé qui lie le contact à une box enrôlée, ce qui remplace l'invitation manuelle ;
  - annonces et accusés signés par la box et vérifiés par le destinataire ;
  - refus d'envoyer à une box révoquée ou expirée, ou d'en recevoir ;
  - affichage dans la console.
