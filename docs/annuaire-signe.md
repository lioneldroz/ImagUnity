# Annuaire signé

> Objectif (pilote, priorité 1 du [plan](plan-de-realisation.md)) : savoir **qui est qui** entre les box d'une organisation. Chaque box est enrôlée par le responsable de l'organisation, qui signe son entrée ; les box vérifient ces signatures avant d'échanger et refusent une box révoquée. Conforme au § 9 de la [spécification IU-T](specification-protocole.md), et utilisable dès maintenant avec le transport SimpleX.
> Statut : **étapes A, B et C1 faites** : socle cryptographique, outil du responsable, service d'annuaire, et intégration dans la box (identité, synchronisation, liaison des partenaires, refus des box non vérifiées ou révoquées). Étape C2 à venir.

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

## 5. Service d'annuaire (étape B)

Module `directory-server` : une application Spring Boot autonome, sur **PostgreSQL** (Flyway), servie sur le port 8090.

| Requête | Accès | Réponse |
|---|---|---|
| `GET /directory/v1/organization` | libre | organisation, clé publique et empreinte courte du responsable (pour information : chaque box épingle sa copie, vérifiée hors bande) |
| `GET /directory/v1/entries` | libre | entrée courante (numéro le plus haut) de chaque box |
| `GET /directory/v1/entries/{boxId}` | libre | entrée courante d'une box ; 404 si inconnue |
| `GET /directory/v1/entries/{boxId}/history` | libre | toutes ses entrées, par numéro croissant |
| `POST /directory/v1/entries` | compte du responsable (HTTP Basic) | 201 publiée, 200 déjà publiée à l'identique, 409 retour en arrière ou autre entrée sous le même numéro, 422 entrée invalide (signature, organisation, format) |

- **Le service revérifie** chaque dépôt avec la clé publique de l'organisation (`IMAGUNITY_DIRECTORY_ORGANIZATION_KEY`). C'est une défense en profondeur : les box revérifient tout, le service n'étant pas de confiance.
- **Historique** : chaque entrée publiée est gardée, une ligne par box et par numéro.
- **Lecture libre** : les entrées sont signées et ne contiennent que des clés publiques, des noms et des adresses de transport. Les réserver aux box enrôlées est possible plus tard (signature des requêtes, § 6.4 de la spécification).
- **Compte de dépôt** : `IMAGUNITY_DIRECTORY_PUBLISHER_USER` (par défaut `responsable`) et `IMAGUNITY_DIRECTORY_PUBLISHER_PASSWORD`, en clair ou haché `{bcrypt}`. Sans mot de passe, il est généré et journalisé au démarrage.
- **Publication** depuis l'outil du responsable : `publier --annuaire <url> --entree <fichier>`. Le compte passe par `IMAGUNITY_ANNUAIRE_COMPTE=utilisateur:mot-de-passe`, plutôt que par l'option `--compte` qui est visible dans la liste des processus.
- **Compose** : services `directory` et `directory-db` sous le profil `annuaire` (`docker compose --profile annuaire up -d`), avec la clé publique de l'organisation dans `deploy/annuaire/organisation.pub.json`.

Vérifié en réel sur PostgreSQL 17 :
- migration appliquée, deux box publiées, publication en double « déjà publiée » ;
- révocation publiée, puis retour en arrière refusé (409) et dépôt sans compte refusé (401) ;
- entrée relue depuis l'annuaire, puis revérifiée `ACTIVE` avec la clé épinglée ;
- historique complet en base.

Le service fonctionne aussi dans le Compose. Les tests automatisés tournent sur H2 en mode PostgreSQL.

## 6. Intégration dans la box (étape C1)

Activée par `IMAGUNITY_DIRECTORY_ENABLED=true`. Sans elle, la box fonctionne comme avant : tout partenaire appairé est accepté.

| Réglage | Rôle |
|---|---|
| `IMAGUNITY_DIRECTORY_URL` | service d'annuaire de l'organisation |
| `IMAGUNITY_DIRECTORY_ORGANIZATION_KEY` | clé publique du responsable (`.pub.json`) |
| `IMAGUNITY_DIRECTORY_ORGANIZATION_CODE` | son empreinte courte, **vérifiée hors bande** avec le responsable puis recopiée ici. Si elle ne correspond pas à la clé installée, la box **refuse de démarrer** et affiche l'empreinte de la clé |
| `IMAGUNITY_DIRECTORY_SYNC_INTERVAL` | intervalle de lecture de l'annuaire (5 min par défaut) |

- **Identité de la box** : générée au premier démarrage (`data/identite-box.json`, droits 600, et son identifiant dans `.id`), puis relue.
- **Enrôlement** : la console propose « Télécharger la demande d'enrôlement » (`GET /api/v1/directory/enrollment-request`). La demande est signée par la box. Le responsable l'enrôle avec son outil, vérifie avec l'installateur l'empreinte affichée des deux côtés, puis publie l'entrée.
- **Synchronisation** : au démarrage, puis à intervalle régulier. Chaque entrée est revérifiée avec la clé épinglée ; un retour en arrière est refusé, car les numéros vus sont gardés en base. Les entrées sont gardées en cache, si bien que la box fonctionne même si l'annuaire est injoignable.
- **Liaison des partenaires**, par une poignée de main signée en défi-réponse :

  ```
  A → B : hello {boxId: A, challenge: cA}
  B → A : proof {boxId: B, challenge: cA, signature de B sur « IU1-hello, cA, B, A »}
  ```

  A vérifie que `cA` est le défi qu'il a envoyé à ce contact, que B est actif dans l'annuaire et que la signature est valide avec la clé de B publiée dans l'annuaire. Le contact SimpleX est alors **lié** à la box B. La preuve couvre le défi frais de A et les deux identifiants : une autre box ne peut pas la rejouer. La poignée de main est relancée après chaque synchronisation pour les partenaires pas encore liés.
- **Contrôle d'accès** : on n'envoie qu'à un partenaire lié dont la box est active (sinon le transfert passe en échec avec le motif), et l'on ne reçoit que d'un tel partenaire (fichiers et annonces ignorés et journalisés). Les motifs de refus :
  - « Partenaire « box-b » pas encore vérifié par l'annuaire » ;
  - « Box du partenaire « box-b » révoquée dans l'annuaire » ;
  - « Entrée d'annuaire … expirée » ;
  - « Box … absente de l'annuaire ».
- **Console** : carte « Annuaire de l'organisation » (identité, empreinte, demande d'enrôlement, dernière synchronisation, box connues et leur état), et état vérifié ou révoqué de chaque partenaire.

![Carte Annuaire de la console (box A, données d'essai)](images/annuaire-console.png)

**Compose** : `compose.annuaire.yml` active l'annuaire dans les deux box.

```bash
export IMAGUNITY_DIRECTORY_ORGANIZATION_CODE=<empreinte vérifiée hors bande>
docker compose -f docker-compose.yml -f compose.annuaire.yml --profile annuaire up -d
```

**Vérifié en réel**, avec deux box, les relais SimpleX, l'annuaire et PostgreSQL :
1. Avant l'enrôlement, le transfert est refusé : « pas encore vérifié par l'annuaire ».
2. Les box sont enrôlées à partir de leur demande téléchargée sur la console. Les empreintes affichées par l'outil du responsable sont exactement celles des consoles des box.
3. Environ 10 s après la publication, chaque box a lié son partenaire.
4. Le transfert réussit : 100 instances identiques sur 100, accusé en 12,6 s.
5. Après la révocation de B, la box A la voit à la synchronisation suivante, et le transfert est refusé : « révoquée dans l'annuaire ».

## 7. Étape suivante (C2)

- **Appairage automatique par l'annuaire** : chaque box publie son adresse de contact SimpleX dans sa demande d'enrôlement. Les box de l'organisation se connectent d'elles-mêmes, puis se lient par la poignée de main, sans invitation manuelle.
- **Annonces et accusés signés** par la box et vérifiés par le destinataire : c'est la non-répudiation, conformément au § 8.4 de la spécification.
- **Clés privées** protégées par phrase de passe ou par support matériel (pilote).
