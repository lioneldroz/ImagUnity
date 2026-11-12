# ImagUnity — Spécification du protocole de transport (IU-T)

> **Statut : v0.6, en service** : étapes 1 à 5 faites (module `protocol`, relais IU-FILE et IU-MSG, adaptateur `iu` du `TransportPort`, **bascule** : campagne de la phase 8 passée sur IU-T, SimpleX retiré du code et du Compose ; § 13.4). Étape 6 (performances) en cours : annonce anticipée, remplissage aléatoire et taille de morceau adaptative faits. Reste l'étape 7 (audit externe). Historique en fin de document.
> Objet : définir un transport propre à ImagUnity, inspiré de XFTP et SMP, pour **remplacer SimpleX** (simplex-chat, relais SMP et XFTP) derrière le `TransportPort`.
> Références : [note d'architecture](ImagUnity%20—%20Note%20d'architecture.docx), [plan de réalisation](plan-de-realisation.md), [phase 1](phase1-simplex.md), [annuaire signé](annuaire-signe.md), [chiffrement applicatif](chiffrement-applicatif.md).

---

## 1. Objectifs et périmètre

### 1.1 Pourquoi un protocole propre

| Motivation | Détail |
|---|---|
| Indépendance | Plus de dépendance à simplex-chat ni aux relais SMP et XFTP (Haskell, AGPL-3.0) : code Java maîtrisé, modifiable sans obligation de publication. |
| Adapté à l'imagerie | Pas de limite de taille de fichier, reprise au morceau près, ordre et priorité par série DICOM, progression fine pour la console. |
| Réseau fermé | Les box sont enrôlées et connues de l'annuaire : l'anonymat face à des inconnus, au cœur de SimpleX, n'est pas nécessaire. Le protocole peut être plus simple. |
| Une seule pile | Plus de processus externe ni d'API WebSocket locale non authentifiée. |

### 1.2 Ce que le protocole conserve de la note d'architecture

1. **Connexions sortantes uniquement** : les box n'écoutent sur aucun port accessible depuis Internet ; tout passe par HTTPS sortant (port 443).
2. **Poste restante** : émetteur et destinataire n'ont pas besoin d'être connectés en même temps.
3. **Relais aveugle** : il stocke des morceaux chiffrés sans en avoir les clés, et ne sait pas quelle box retire quoi.
4. **Autorisation par capacité** : retirer un morceau exige un identifiant opaque et une signature avec la clé de retrait correspondante, pas une identité.
5. **Durée de séjour courte et bornée**, avec purge après retrait ou expiration (argument « transit, pas hébergement » vis-à-vis de l'HDS).
6. **Habilitation décidée chez l'émetteur**, d'après l'annuaire.

### 1.3 Ce sur quoi IU-T s'appuie (déjà réalisé)

IU-T ne réinvente pas ce que le pilote a déjà construit et vérifié :

| Brique | Rôle pour IU-T | Document |
|---|---|---|
| Annuaire signé, suite `IU1` | Identités des box (Ed25519 + ML-DSA-65), clés de chiffrement (X25519 + ML-KEM-768), révocation ; publie l'adresse de la file de chaque box (§ 9) | [annuaire-signe.md](annuaire-signe.md) |
| Chiffrement applicatif des paquets | Chaque paquet est déjà chiffré pour la box destinataire (`.iu1`) : IU-T transporte un fichier chiffré de bout en bout (§ 5) | [chiffrement-applicatif.md](chiffrement-applicatif.md) |
| Annonces et accusés signés | Contenu des messages IU-MSG (§ 8.4) | [annuaire-signe.md § 8](annuaire-signe.md) |
| Clés protégées par le TPM | Protection des clés d'identité de la box | [cles-tpm.md](cles-tpm.md) |
| Orchestrateur | États des transferts, reprise, relance ; inchangé derrière le `TransportPort` | [phase 4](phase4-orchestrateur.md), [phase 6](phase6-accuse.md) |

### 1.4 Hors périmètre de ce document

- La gouvernance de l'annuaire : voir [annuaire-signe.md](annuaire-signe.md). Seul ce dont le protocole a besoin est rappelé au § 9.
- Le format interne du paquet d'examen (manifeste, archive, compression) : il relève du module `packaging`. Le protocole transporte un fichier opaque.

### 1.5 Règle absolue : aucune cryptographie maison

Le protocole **assemble** des primitives standard et éprouvées ; il n'en invente aucune. Toute construction s'appuie sur une référence publiée (RFC, norme NIST, schéma de TLS 1.3). Un **audit de sécurité externe** est un prérequis à la mise en production (§ 13, étape 7).

---

## 2. Vue d'ensemble

```
 Box émettrice                     Relais                         Box réceptrice
┌──────────────┐  HTTPS sortant  ┌──────────────────────┐  HTTPS sortant  ┌──────────────┐
│ paquet .iu1  │ ──────────────► │ IU-FILE  morceaux    │ ◄────────────── │ téléchargement│
│ (déjà chiffré│                 │          (opaques)   │                 │ vérification  │
│  pour B)     │ ──────────────► │ IU-MSG   files de    │ ◄────────────── │ relève (attente│
│ découpage    │   annonce       │          messages    │   longue)       │ longue)       │
└──────┬───────┘                 └──────────┬───────────┘                 └──────┬───────┘
       │ clés et file du destinataire       │ box enrôlées (dépôt)                │
       └──────────────────────────► Annuaire de l'organisation ◄─────────────────┘
```

Deux services sur le relais, sur le même point d'entrée HTTPS :

- **IU-FILE** (équivalent de XFTP) : stockage temporaire de morceaux de taille fixe.
- **IU-MSG** (équivalent de SMP) : files de messages unidirectionnelles pour la signalisation (annonce d'un fichier, accusé de bout en bout).

Un même relais offre les deux services ; une organisation peut en utiliser plusieurs (redondance, proximité réseau), plus tard (§ 13, étape 6).

**Choix de HTTPS** (TLS 1.3) plutôt qu'un protocole binaire dédié : passe tous les pare-feu et proxys qui autorisent le web, outillage standard (répartiteurs de charge, supervision), implémentation directe en Java des deux côtés. En production, le TLS est terminé par un proxy devant le relais ; pour les essais, certificat autosigné épinglé par les box.

---

## 3. Acteurs, clés et modèle de menace

### 3.1 Acteurs

| Acteur | Rôle | Fait confiance à |
|---|---|---|
| Box | Émet et reçoit les examens. Porte toute la sécurité. | Sa propre clé, la clé du responsable de son organisation (épinglée à l'installation). |
| Relais | Stocke temporairement morceaux et messages. | La clé du responsable de chaque organisation qu'il sert (épinglée), pour vérifier que les déposants sont des box enrôlées (§ 6.4). |
| Annuaire | Publie les entrées signées des box de l'organisation. | — (ses entrées sont signées par le responsable ; l'annuaire lui-même n'est pas de confiance). |
| Responsable de l'organisation | Valide l'enrôlement et la révocation des box. | — |

### 3.2 Clés

| Clé | Type | Où elle naît | Durée de vie | Usage |
|---|---|---|---|---|
| Identité de signature de la box | Ed25519 + ML-DSA-65 | Dans la box (protégée par le TPM) | Vie de la box | Signer annonces, accusés et **requêtes de dépôt** (§ 6.3) |
| Identité de chiffrement de la box | X25519 + ML-KEM-768 | Dans la box (protégée par le TPM) | Vie de la box, renouvelable | Recevoir les paquets et les messages chiffrés |
| Clé du responsable | Ed25519 + ML-DSA-65 | Hors ligne, chez le responsable | Longue | Signer les entrées de l'annuaire |
| Clé d'émetteur de morceau | Ed25519 | Box émettrice | Un morceau | Téléverser, supprimer, consulter l'état |
| Clé de retrait de morceau | Ed25519 | Box émettrice, pour chaque destinataire | Un morceau | Télécharger, acquitter |
| Clé de file (réception) | Ed25519 | Box réceptrice | Vie de la file | Relever et acquitter les messages |

La v0.2 prévoyait en plus une **clé de fichier** par transfert et un **certificat client d'enrôlement**. Ils sont abandonnés : le paquet est déjà chiffré pour le destinataire (§ 5), et le dépôt est authentifié par la signature d'identité de la box, vérifiée d'après l'annuaire (§ 6.4).

Remplacement plutôt que restauration (note d'architecture) : aucune clé de box n'est sauvegardée. Une box en panne est remplacée et l'ancienne révoquée.

### 3.3 Modèle de menace

| Adversaire | Capacités supposées | Ce que le protocole garantit | Ce qu'il ne garantit pas |
|---|---|---|---|
| Observateur réseau passif | Voit tout le trafic chiffré | Confidentialité du contenu (TLS + chiffrement de bout en bout) | Les métadonnées : qui parle à quel relais, quand, quels volumes |
| Attaquant réseau actif | Intercepte, modifie, rejoue | Intégrité (empreintes, AEAD, signatures), anti-rejeu | Disponibilité (il peut couper la liaison) |
| Opérateur de relais curieux ou compromis | Lit son stockage et ses journaux | Ne peut lire aucun morceau ni message ; ne peut pas relier un retrait à une box par les identifiants | Il voit les volumes, les horaires, les adresses IP des box, **quelle box dépose** (morceaux, messages) et **dans quelle file** elle dépose un message (§ 12, question 5) ; il peut supprimer ou retenir des données (déni de service) |
| Box tierce de l'organisation | Enrôlée, malveillante | Ne peut ni lire ni retirer ce qui ne lui est pas destiné ; ne peut pas se faire passer pour une autre box (signatures) | Elle peut saturer le relais ou une file (limites de débit et de volume, § 6.4) |
| Box volée | Accès physique | Révocation dans l'annuaire, prise en compte par les box **et par le relais** (dépôt refusé) ; clés liées au TPM de la box | Les paquets déjà émis vers elle restent récupérables jusqu'à expiration (d'où la durée de séjour courte) |
| Annuaire compromis | Modifie les entrées | Les entrées sont signées par le responsable : une substitution de clé ou d'adresse de file est détectée | Il peut retirer des entrées (déni de service) |
| Clé du responsable compromise | Signe de fausses entrées | — | **Point critique** : détournement possible. Parade : clé hors ligne, chiffrée par phrase de passe |
| Enregistrement aujourd'hui, déchiffrement demain | Stocke le trafic, ordinateur quantique futur | Paquets et messages chiffrés par KEM hybride X25519 + ML-KEM-768 ; signatures hybrides | — |

---

## 4. Primitives cryptographiques

### 4.1 Suite `IU1`

Toutes les primitives sont disponibles **dans le JDK 25**, sans bibliothèque native ; elles sont déjà employées par l'annuaire et le chiffrement applicatif.

| Fonction | Primitive | Référence | API Java |
|---|---|---|---|
| Canal box ↔ relais | TLS 1.3 uniquement | RFC 8446 | `javax.net.ssl`, `java.net.http.HttpClient` |
| Accord de clé hybride (paquets, messages) | DHKEM X25519 + ML-KEM-768, combinés par HKDF-SHA256 | RFC 9180, FIPS 203, RFC 5869 | `KEM "DHKEM"`, `KEM "ML-KEM"`, `KDF "HKDF-SHA256"` |
| Chiffrement authentifié (paquets, messages) | AES-256-GCM | NIST SP 800-38D | `Cipher "AES/GCM/NoPadding"` |
| Signature d'identité (annonces, accusés, dépôts) | Ed25519 + ML-DSA-65, les deux valides | RFC 8032, FIPS 204 | `Signature "Ed25519"`, `Signature "ML-DSA-65"` |
| Signature de capacité (morceaux, files) | Ed25519 | RFC 8032 | `Signature "Ed25519"` |
| Empreintes | SHA-256 | FIPS 180-4 | `MessageDigest "SHA-256"` |
| Aléa | `SecureRandom` (DRBG) | NIST SP 800-90A | `SecureRandom` |

La v0.2 retenait ChaCha20-Poly1305 ; la v0.3 retient AES-256-GCM, déjà employé par le chiffrement applicatif et la protection des clés : une seule construction à auditer.

### 4.2 Hybride post-quantique

- **Paquets et messages** : chiffrés pour la clé hybride X25519 + ML-KEM-768 du destinataire ; la clé reste sûre tant qu'**au moins une** des deux primitives tient.
- **Signatures d'identité** : double signature Ed25519 + ML-DSA-65, les deux devant être valides.
- **Clés de capacité** (morceaux, files) : Ed25519 seul. Elles ne protègent que l'accès pendant la durée de séjour, pas la confidentialité : une attaque quantique future ne donne rien.
- **Canal TLS** : groupe hybride `X25519MLKEM768` si la pile TLS le propose ; sinon X25519, la confidentialité de bout en bout ne dépendant pas de TLS.

### 4.3 Agilité cryptographique

Chaque structure porte un identifiant de suite (`IU1`). Une nouvelle suite (`IU2`) peut être introduite sans casser les box existantes ; une suite compromise peut être refusée par configuration.

---

## 5. Découpage d'un paquet

### 5.1 Le paquet est déjà chiffré

Le module `packaging` produit le paquet d'examen ; le chiffrement applicatif le chiffre pour la box destinataire (fichier `.iu1`, [chiffrement-applicatif.md](chiffrement-applicatif.md)) : seul le destinataire peut l'ouvrir, et toute altération est détectée à l'ouverture. IU-T transporte ce fichier tel quel ; **il n'ajoute pas de seconde couche de chiffrement** (décision v0.3).

Conséquence : **IU-T exige l'annuaire et le chiffrement applicatif**. Un paquet en clair n'est jamais confié à IU-T.

### 5.2 Taille des morceaux

- Taille de morceau `S` fixée pour tout le transfert, choisie parmi un **ensemble fermé** : 256 Kio, 1 Mio, 4 Mio (par défaut), 8 Mio.
- **Taille adaptée à la liaison** (v0.6, `IMAGUNITY_IU_CHUNK_SIZE=auto` par défaut, ou une taille fixe) : la box mesure le débit de chaque téléversement de morceau vers le relais (moyenne glissante, poids 0,3 à la dernière mesure, gardée dans son dossier d'état) et choisit pour chaque envoi la plus grande taille admise qui se téléverse en **3 s au plus**, sans dépasser **512 morceaux** par paquet (description courte, préparation rapide). Sans mesure : 4 Mio. Liaison lente : petits morceaux, donc moins de travail perdu sur une coupure, et, avec l'annonce anticipée, une restitution qui suit de près la fin du téléversement ; liaison rapide : gros morceaux, donc moins de requêtes.
- Le dernier morceau est **complété jusqu'à `S`** : le relais ne voit que des morceaux de tailles standard. Depuis la v0.5, le remplissage est **pseudo-aléatoire** : flux AES-256-CTR (nonce nul) d'une clé de remplissage de 32 octets tirée pour chaque envoi et gardée dans son état, donc reproductible à la reprise. Des zéros laissaient voir au relais la taille exacte du paquet chiffré (constat de la campagne sur IU-T, § 13.4). Le destinataire ne vérifie que l'empreinte du morceau, qui couvre le remplissage ; un remplissage par des zéros (vecteurs de test, envois antérieurs) reste accepté. Le nombre de morceaux révèle la taille approximative du paquet, ce qui est accepté (les volumes font partie des métadonnées visibles).

### 5.3 Empreintes

1. Découper le paquet chiffré en `n` morceaux de `S` octets, en complétant le dernier ; sa longueur réelle est dans la description (§ 7).
2. Calculer `H_i = SHA-256(morceau_i complété)` pour chaque morceau, et `D = SHA-256(paquet chiffré)`.

`H_i` permet au destinataire de vérifier chaque morceau dès son téléchargement (et d'en refaire un seul en cas d'erreur) ; `D`, porté aussi par l'annonce signée, vérifie le paquet réassemblé. Un relais ne peut ni réordonner, ni tronquer, ni substituer un morceau sans que la vérification échoue.

---

## 6. IU-FILE : stockage des morceaux sur le relais

### 6.1 Principe de capacité

Pour chaque morceau, la box émettrice génère :

- une paire de clés **émetteur** `(sk_snd, pk_snd)` ;
- pour **chaque destinataire** et chaque relais qui héberge le morceau, une paire de clés de **retrait** `(sk_rcv, pk_rcv)`.

Le relais ne reçoit que les clés publiques et attribue des **identifiants opaques** aléatoires (32 octets) : un `senderId`, et un `recipientId` par clé de retrait. Les clés privées de retrait voyagent jusqu'au destinataire dans la description de fichier, chiffrée de bout en bout. Le relais ne peut donc pas relier un `senderId` à un `recipientId` par leur forme, ni savoir qui retire.

### 6.2 API

Toutes les requêtes passent par TLS. Les requêtes authentifiées portent les en-têtes de signature (§ 6.3).

| Opération | Requête | Authentification | Réponse |
|---|---|---|---|
| Créer un morceau | `POST /iu/v1/chunks` corps : `{suite, size, digest, senderKey, recipientKeys[], ttl}` | Signature d'identité de la box (dépôt, § 6.4) | `{senderId, recipientIds[]}` |
| Téléverser | `PUT /iu/v1/chunks/{senderId}` corps : le morceau | Signature `sk_snd` | `204` ; le relais vérifie `size` et `digest` |
| Télécharger | `GET /iu/v1/chunks/{recipientId}[?wait=<s>]` (`Range` autorisé) | Signature `sk_rcv` | `200` + morceau ; `425 Too Early` + `Retry-After` si pas encore téléversé |
| Acquitter | `POST /iu/v1/chunks/{recipientId}/ack` | Signature `sk_rcv` | `204` ; accès révoqué pour ce destinataire |
| Supprimer | `DELETE /iu/v1/chunks/{senderId}` | Signature `sk_snd` | `204` |
| État | `GET /iu/v1/chunks/{senderId}` | Signature `sk_snd` | `{uploaded, recipientsAcked}` (sans identifier les destinataires) |

Règles du relais :

- un morceau est **supprimé** quand tous ses destinataires l'ont acquitté, à la demande de l'émetteur, ou à l'expiration de son `ttl` ;
- `ttl` est plafonné par la configuration du relais (proposition : 72 h par défaut, 7 jours au plus) ;
- un morceau créé mais pas téléversé expire au bout d'une durée courte (proposition : 24 h) ;
- un morceau pas encore téléversé répond `425 Too Early` ; avec `wait`, le relais garde la requête ouverte jusqu'à la fin du téléversement ou jusqu'au délai demandé (plafonné, proposition : 30 s) ;
- le relais ne journalise aucun identifiant de morceau associé à une adresse IP au-delà de ce qu'exige la lutte contre les abus.

### 6.3 Signature des requêtes

En-têtes `IU-Key-Id` (identifiant du morceau ou de la file, ou identifiant de la box pour un dépôt), `IU-Timestamp` (UTC, secondes), `IU-Request-Id` (16 octets aléatoires) et `IU-Signature`.

La signature porte sur la chaîne canonique :

```
"IU1-req" ‖ méthode ‖ chemin ‖ IU-Timestamp ‖ IU-Request-Id ‖ SHA-256(corps)
```

(chaque élément séparé par un saut de ligne ; empreinte en hexadécimal). Requêtes de **capacité** : signature Ed25519 de la clé du morceau ou de la file. Requêtes de **dépôt** : double signature d'identité de la box (Ed25519 + ML-DSA-65, les deux valides), comme les messages signés de l'annuaire.

Le relais refuse une requête dont l'horodatage s'écarte de plus de 5 minutes, ou dont le `IU-Request-Id` a déjà été vu dans cette fenêtre (anti-rejeu). Une variante par défi (le relais fournit un nonce) est à étudier si l'horloge des box n'est pas fiable (§ 12, question 2).

### 6.4 Réserver le dépôt aux box enrôlées

- **Dépôt** (création de morceau, création de file, dépôt de message) : réservé aux **box enrôlées et actives**. La requête est signée par l'identité de la box (§ 6.3) ; le relais vérifie la signature d'après l'**entrée d'annuaire** de la box. Il épingle la clé du responsable de chaque organisation qu'il sert et lit l'annuaire comme une box (même bibliothèque, mêmes règles : signature du responsable, validité, révocation, pas de retour en arrière). Une box révoquée ne peut plus rien déposer. **Décision v0.3** : pas de certificats clients TLS, donc pas d'infrastructure de certificats à construire.
- **Retrait** : **aucun contrôle d'identité**, uniquement la capacité (identifiant + signature). Un contrôle d'identité au retrait obligerait le relais à savoir qui récupère quoi, ce que la note proscrit.

Le relais applique des limites de débit et de volume par box déposante (protection contre la saturation par une box malveillante).

### 6.5 Reprise

L'unité de reprise est le morceau. La box enregistre en base l'état de chaque morceau (créé, téléversé) et reprend après une coupure sans recommencer le transfert. `Range` permet aussi de reprendre un téléchargement en cours de morceau. L'ordre et le parallélisme sont libres côté box (savoir-faire propriétaire).

### 6.6 Annonce anticipée

**Décision v0.3** : la v1 annonçait un fichier **une fois tous ses morceaux téléversés** (mode `complete`, comme XFTP). **Depuis la v0.5 (étape 6), l'annonce anticipée (mode `early`) est le défaut** (`IMAGUNITY_IU_EARLY=false` pour revenir au mode `complete`).

La box crée tous les morceaux (requêtes légères), envoie aussitôt la description (mode `early`), puis téléverse ; le destinataire télécharge en parallèle, en attente longue (`wait`) sur chaque morceau pas encore disponible (`425 Too Early` au-delà de l'attente, aussitôt redemandé). Il attend un morceau jusqu'à l'expiration du fichier, au lieu des 15 minutes du mode `complete`. Le temps total tend vers celui du plus lent des deux, au lieu de leur somme ([résultats de la phase 1](phase1-simplex.md#5-résultats)).

- **Abandon** : un envoi en échec retire ses morceaux du relais ; le destinataire, qui les attendait, reçoit `404` (refus définitif) et le téléchargement échoue aussitôt. Le message `file.cancel` prévu n'est donc pas nécessaire.
- **Contrepartie** : le relais voit un retrait commencer avant la fin du dépôt, ce qui l'aide à associer dépôt et retrait par leurs horaires. Il les associe déjà par les acquittements, sur un relais unique ; accepté.
- L'annonce du transfert (message signé de l'orchestrateur) part dès que tous les paquets sont confiés au transport (v0.7, [priorité des séries](priorite-dicom.md)) ; le destinataire restitue chaque paquet dès qu'il l'a reçu et que l'annonce est arrivée, les séries prioritaires d'abord. `FileSent` suit toujours la fin du téléversement.

---

## 7. Description de fichier

La description donne au destinataire tout ce qu'il faut pour retrouver et vérifier le paquet chiffré.

```json
{
  "suite": "IU1",
  "transferId": "<16 octets aléatoires, base64url>",
  "mode": "complete",
  "fileName": "<groupe>-1-of-4.tar.zst.iu1",
  "chunkSize": 4194304,
  "chunkCount": 312,
  "size": 1306525184,
  "digest": "<D = SHA-256(paquet chiffré), hexadécimal>",
  "chunks": [
    {
      "index": 0,
      "digest": "<H_0, hexadécimal>",
      "replicas": [
        { "relay": "https://relais-1.org.example", "recipientId": "<…>", "retrievalKey": "<sk_rcv, PKCS#8 base64>" }
      ]
    }
  ],
  "expiresAt": "2026-11-14T13:00:00Z"
}
```

- Elle contient des **clés de retrait** : elle n'est jamais envoyée en clair, mais dans un message chiffré et signé (§ 8.4).
- **Encodage** : JSON. Pas de canonicalisation : comme pour l'annuaire, les signatures portent sur les **octets exacts** du texte signé ([annuaire-signe.md § 2.1](annuaire-signe.md)), ce qui évite RFC 8785.
- **Mode** : `complete` (annoncé une fois téléversé) ou `early` (annoncé dès la création des morceaux, § 6.6).
- **Taille** : environ 280 octets par morceau et par réplique. Un paquet de 900 Mo (taille maximale actuelle d'un paquet) en morceaux de 4 Mio donne environ 60 Kio de description. Mise en œuvre (étape 4) : une description qui dépasse un message part **en fragments** de 20 000 caractères (messages `iu.file`), plutôt qu'en description de redirection.

---

## 8. IU-MSG : files de signalisation

### 8.1 Principe

Chaque box crée une **file de réception** sur un relais de son organisation. La file a deux identifiants opaques :

- `sendId` : permet de **déposer** un message ; publié dans l'entrée d'annuaire de la box (§ 9) ;
- `recvId` : permet de **relever** les messages ; connu de la box seule, protégé par la clé de file.

La box relève sa file par connexions sortantes (attente longue). Aucune connexion entrante n'est nécessaire. Comme l'adresse de la file est dans l'annuaire signé, **l'appairage passe par l'annuaire** : plus de lien d'invitation ni de poignée de main sur un canal anonyme.

### 8.2 API

| Opération | Requête | Authentification | Réponse |
|---|---|---|---|
| Créer une file | `POST /iu/v1/queues` corps : `{suite, queueKey, sendId?}` | Signature d'identité de la box (dépôt) | `{sendId, recvId}` ; `sendId` réservé par la box (§ 9) ou tiré par le relais |
| Déposer | `POST /iu/v1/queues/{sendId}/messages` corps : enveloppe (§ 8.4) | Signature d'identité de la box (dépôt) | `202 {messageId}` |
| Relever | `GET /iu/v1/queues/{recvId}/messages?wait=30` | Signature clé de file | `200 [messages]` ou `204` après attente |
| Acquitter | `DELETE /iu/v1/queues/{recvId}/messages/{messageId}` | Signature clé de file | `204` ; message supprimé |
| Renouveler | `POST /iu/v1/queues/{recvId}/rotate` | Signature clé de file | nouveaux `{sendId, recvId}` (à republier dans l'annuaire) |

Les messages non relevés expirent selon la même politique que les morceaux.

### 8.3 Taille des messages

Taille maximale : **64 Kio** par message (contre environ 16 Ko pour SMP). Les messages sont complétés jusqu'à des tailles par paliers (4, 16, 64 Kio) pour limiter ce que leur taille révèle.

### 8.4 Enveloppe et types de messages

Une enveloppe déposée est un **document chiffré pour le destinataire** avec la construction du chiffrement applicatif (KEM hybride X25519 + ML-KEM-768, HKDF-SHA256, AES-256-GCM), appliquée à un contenu en mémoire :

```
"IU1M" ‖ version ‖ en-tête (destinataire, encapsulations) ‖ AES-256-GCM(clé dérivée, contenu signé ‖ remplissage)
```

Le **contenu signé** est un message signé de l'annuaire (version 2 : type, identifiant de la box émettrice, corps, double signature Ed25519 + ML-DSA-65), complété d'un **identifiant de message unique** et d'un **horodatage**. Le destinataire vérifie la signature d'après l'annuaire et rejette les messages déjà vus (anti-rejeu par identifiant de message, gardé pendant la durée de séjour).

Le relais ne voit ni le type, ni le contenu ; il sait quelle box dépose et dans quelle file (§ 3.3).

| Type | Sens | Corps |
|---|---|---|
| `file.announce` | émetteur → destinataire | annonce de transfert (paquets et leurs empreintes, comme aujourd'hui) **et** une description de fichier (§ 7) par paquet |
| `file.ack` | destinataire → émetteur | accusé de transfert (restitué / en échec, instances, empreinte des paquets), comme aujourd'hui |
| `file.cancel` | émetteur → destinataire | abandon d'un transfert annoncé (annonce anticipée, étape 6) |
| `ping` | les deux | diagnostic de liaison |

---

## 9. Ce que le protocole attend de l'annuaire

L'entrée de box signée par le responsable ([annuaire-signe.md § 2.2](annuaire-signe.md)) porte déjà les clés de signature et de chiffrement. IU-T y ajoute l'**adresse de la file de réception**, dans le champ `transport` existant :

```json
"transport": { "iu": "https://relais-1.org.example/iu/v1/queues/<sendId>" }
```

- Le relais ne crée de file que pour une box déjà enrôlée et active (§ 6.4) : une box neuve **réserve** donc l'identifiant de dépôt de sa future file (32 octets aléatoires) et sa **demande d'enrôlement** porte l'adresse correspondante ; le responsable la signe dans l'entrée. Une fois l'entrée publiée, la box crée sa file en demandant cet identifiant ; le relais ne l'accepte que s'il est celui de l'adresse publiée dans l'entrée signée de **cette** box (sinon `409`), et pas encore pris : aucune autre box ne peut s'approprier l'adresse. Une substitution d'adresse par un annuaire compromis est détectée par la signature de l'entrée.
- Une box enrôlée avant de passer à IU-T (entrée sans adresse) crée sa file avec un identifiant tiré par le relais ; sa demande d'enrôlement suivante porte cette adresse, à faire signer une fois.
- Avant tout envoi, la box émettrice vérifie l'entrée du destinataire (signature, validité, révocation) et y prend ses clés de chiffrement et l'adresse de sa file.
- Le **relais** lit le même annuaire pour vérifier les déposants (§ 6.4).
- Une révocation empêche tout nouvel envoi et tout nouveau dépôt ; les paquets déjà émis restent récupérables jusqu'à leur expiration.

---

## 10. Déroulé complet d'un envoi

1. Le PACS envoie l'examen à la box (C-STORE ou STOW-RS) ; la box identifie le destinataire (route fixe ou règles de routage).
2. La box lit l'entrée du destinataire dans l'annuaire et vérifie sa signature.
3. Elle produit les paquets et les chiffre pour le destinataire (chiffrement applicatif).
4. Pour chaque paquet, elle choisit `S`, découpe et calcule les empreintes (§ 5), crée les morceaux sur le relais (§ 6), puis les téléverse. État enregistré morceau par morceau.
5. Une fois tous les paquets téléversés, elle construit l'annonce et les descriptions (§ 7), les chiffre et les signe pour le destinataire, et les dépose dans sa file (§ 8) : état `ANNOUNCED`.
6. La box réceptrice relève le message, vérifie la signature de l'émetteur, qu'il est enrôlé et actif, et qu'il est appairé.
7. Elle télécharge les morceaux, vérifie chaque `H_i` puis `D`, et acquitte chaque morceau auprès du relais.
8. Elle déchiffre, dépaquette, vérifie l'empreinte de chaque instance DICOM, pousse l'examen vers le PACS.
9. Elle renvoie un accusé signé : l'émetteur passe le transfert à `ACKNOWLEDGED` et supprime ce qui reste de ses morceaux.

Les étapes 4 et 7 reprennent là où elles s'étaient arrêtées après une coupure.

---

## 11. Comparaison avec XFTP et SMP

| Aspect | XFTP / SMP (SimpleX) | IU-T |
|---|---|---|
| Langage, licence | Haskell, AGPL-3.0 | Java, propriétaire (spécification publiable) |
| Transport | TLS, protocole binaire dédié | HTTPS (TLS 1.3) |
| Identités | Aucune, contacts anonymes | Box enrôlées, annuaire signé |
| Dépôt | Ouvert ou protégé par mot de passe | Box enrôlées et actives (signature d'identité vérifiée d'après l'annuaire) |
| Retrait | Capacité (identifiant + signature) | Identique |
| Chiffrement de bout en bout | Par le protocole | Par le chiffrement applicatif des paquets, hybride post-quantique |
| Taille de fichier | Limitée (1 Go en v5.0.0) | Non limitée par le protocole |
| Taille de morceau | Quelques tailles fixes | Ensemble fermé, choisi selon la liaison |
| Reprise | Par morceau | Par morceau, plus `Range` dans un morceau |
| Début du téléchargement | Après le téléversement complet | Idem en v1 ; dès l'annonce avec l'annonce anticipée (étape 6) |
| Appairage | Lien d'invitation ou adresse de contact | Par l'annuaire signé |
| Double ratchet, confidentialité persistante | Oui (messages) | Non : encapsulation neuve par paquet et par message (§ 12, question 1) |
| Métadonnées | Minimisées (pas d'identité) | Le relais voit les box déposantes, les files où elles déposent, les adresses IP ; il ne voit ni le contenu, ni qui retire quoi |

---

## 12. Questions ouvertes

1. **Confidentialité persistante** : l'encapsulation neuve par paquet et par message protège chacun isolément, mais la compromission de la clé de chiffrement d'une box exposerait ce qui est encore stocké sur les relais. Parade prévue : rotation des clés de chiffrement par réémission de l'entrée, et durée de séjour courte.
2. **Horloge des box** : l'anti-rejeu par horodatage suppose une horloge juste. Les sites éloignés ont-ils un NTP fiable, ou faut-il la variante par défi ?
3. **Opérateur des relais** : organisation, région, ou établissements (question déjà ouverte dans la note). La spécification permet les trois.
4. **Métadonnées de trafic** : faut-il masquer davantage le trafic (envois de remplissage, horaires lissés) ? Coût en bande passante sur les liaisons lentes.
5. **Couple émetteur / file au dépôt de message** : le dépôt étant authentifié par l'identité de la box, le relais sait quelle box dépose dans quelle file, donc à qui elle écrit. La v0.2 sous-estimait ce point (un certificat client révèle la même chose). Si l'organisation l'exige, pistes : relais opéré par l'organisation elle-même (le couple lui est connu de toute façon), ou jetons de dépôt anonymes (signatures aveugles) dans une version ultérieure.

Questions de la v0.2 tranchées : JSON sans canonicalisation (signature des octets exacts) ; PostgreSQL pour les métadonnées du relais ; adaptateur SimpleX gardé en parallèle jusqu'à la bascule (§ 13).

---

## 13. Mise en œuvre

### 13.1 Modules

| Module | Contenu |
|---|---|
| `backend/protocol` (nouveau) | Requêtes signées (chaîne canonique, anti-rejeu), enveloppe des messages, découpage et empreintes, formats (description, annonce). Partagé par la box et le relais. Couvert par des vecteurs de test. S'appuie sur `backend/directory` (suite `IU1`, annuaire). |
| `backend/transport` | Nouvel adaptateur `iu` qui implémente `TransportPort`, à côté de `simplex` ; le choix se fait par configuration. |
| `backend/relay` (nouvelle application) | Relais IU-FILE + IU-MSG : Spring Boot, PostgreSQL pour les métadonnées, disque pour les morceaux ; lit l'annuaire pour vérifier les déposants. |

### 13.2 Correspondance avec le `TransportPort`

| `TransportPort` | Adaptateur `iu` |
|---|---|
| `contactAddress()` | Adresse de la file de réception (créée au premier appel), publiée par la demande d'enrôlement |
| `createInvitation()`, `acceptInvitation()`, `acceptContactRequest()` | Sans objet : appairage par l'annuaire (la console appaire une box de l'annuaire, comme aujourd'hui, sans échange sur le transport) |
| `sendMessage(peer, text)` | Dépôt d'une enveloppe dans la file du partenaire (§ 8) |
| `sendFile(peer, file)` | Découpage, création et téléversement des morceaux ; `FileSent` quand tout est téléversé ; la description part avec l'annonce |
| `receiveFile(fileId, target)` | Téléchargement des morceaux, vérification, réassemblage |
| `discardSentFile(fileId)` | Suppression des morceaux sur le relais |
| `catchUp()` | Relève de la file : les messages non acquittés sont rejoués |
| Événements | `MessageReceived`, `FileOffered`, `FileReceived`, `FileSent`, `FileFailed`, `FileProgress`, comme avec SimpleX |

L'orchestrateur, le DICOM, l'empaquetage et la console ne changent pas.

### 13.3 Étapes

Chaque étape se termine par des tests automatiques, un essai réel sur la pile Docker, la documentation, puis la fusion.

| Étape | Contenu | Résultat vérifiable |
|---|---|---|
| 0 | Cette spécification v0.3 | Validée |
| 1 | Module `protocol` : requêtes signées, enveloppe, découpage, formats, vecteurs de test | Mêmes octets produits côté box et côté relais |
| 2 | Relais IU-FILE et son client : création, téléversement, téléchargement (`Range`), acquittement, suppression ; expiration, quotas, purge ; dépôt réservé aux box enrôlées | Fichier de 2 Go par le relais, coupure en cours de route comprise ; box révoquée refusée |
| 3 | Relais IU-MSG et son client : files, dépôt, relève par attente longue, acquittement, expiration | Messages entre deux box par le relais, box éteinte comprise |
| 4 | Adaptateur `iu` du `TransportPort` ; adresse de file dans l'annuaire ; appairage par l'annuaire ; reprise par morceau en base | Transfert de bout en bout sur IU-T, accusé compris |
| 5 | Bascule : campagne de la phase 8 sur IU-T (nominal, coupure, destinataire éteint, 2 Go, pare-feu et scan de ports), SimpleX en parallèle ; Compose sans SimpleX ; retrait de SimpleX | Résultats au moins égaux à SimpleX ; plus aucun conteneur SimpleX |
| 6 | Plus tard : annonce anticipée, plusieurs relais, taille de morceau adaptative (recoupe la priorité 6 du plan) | Gain mesuré sur liaison bridée |
| 7 | Audit de sécurité externe et corrections | Rapport de l'auditeur |

### 13.4 Avancement

**Étape 1 — module `protocol` (fait).** `backend/protocol`, partagé par la box et le relais :

| Classe | Rôle | § |
|---|---|---|
| `SignedRequest` | Chaîne canonique, signature de capacité (Ed25519) et de dépôt (Ed25519 + ML-DSA-65, forme `<ed25519>.<mlDsa65>` en base64url), en-têtes `IU-*` | 6.3 |
| `ReplayGuard` | Fenêtre de 5 minutes, identifiants de requête déjà vus, mémoire bornée (au-delà, refus plutôt qu'oubli) | 6.3 |
| `ChunkLayout`, `Chunks` | Tailles de morceau admises, remplissage par des zéros, empreintes ; réassemblage dans n'importe quel ordre, avec vérification de l'empreinte et du remplissage de chaque morceau | 5 |
| `FileDescription` | Description de fichier, validée à la lecture (suite, identifiants, découpage, ordre des morceaux, adresses de relais, clés de retrait) | 7 |
| `MessageEnvelope` | Enveloppe chiffrée pour le destinataire, contenu complété aux paliers de 4, 16 et 64 Kio | 8.3, 8.4 |
| `SignedMessage` | Contenu signé (identifiant de message, date, type, box émettrice, corps), vérifié d'après l'annuaire | 8.4 |

Précisions apportées par l'implémentation : la chaîne canonique sépare ses éléments par un saut de ligne et donne l'empreinte du corps en hexadécimal ; les identifiants de requête, de transfert et de message font 16 octets (22 caractères base64url), ceux des morceaux 32 octets ; l'enveloppe est préfixée par `IU1M`, sa clé est à usage unique (nonce nul) et l'empreinte de l'en-tête est en données associées.

**Vecteurs de test** (`backend/protocol/src/test/resources/vecteurs-iu1.json`), figés : la box et le relais doivent produire et accepter exactement ces octets. Les valeurs déterministes sont recalculées à chaque test (chaîne canonique, signature Ed25519 d'une clé fixe, empreintes des morceaux d'un paquet de 600 000 octets) ; les pièces aléatoires (signature de dépôt, enveloppe, message signé) sont vérifiées ou ouvertes avec les clés fournies.

**Étape 2 — relais IU-FILE et son client (fait).**

*Relais* (`backend/relay`, application Spring Boot ; image `deploy/relay/Dockerfile`, Compose `deploy/compose.iu.yml`, intégré depuis au Compose de base) :
- API du § 6.2 : création (dépôt), téléversement, téléchargement avec `Range` et attente longue (`wait`, réponse `425 Too Early` + `Retry-After`), acquittement, suppression, état ;
- **dépôt réservé aux box enrôlées et actives** : le relais épingle la clé du responsable (empreinte confirmée, sinon refus de démarrer), lit l'annuaire comme une box (vue locale en base, synchronisation périodique) et vérifie la double signature de la box ;
- capacités Ed25519 par morceau, anti-rejeu (§ 6.3) ;
- téléversement écrit en flux dans un fichier temporaire, vérifié (taille, empreinte) avant d'être mis en place ; renvoi d'un morceau déjà reçu sans effet ; corps trop long coupé (`413`) ;
- morceau supprimé quand tous ses destinataires l'ont acquitté, à la demande de l'émetteur, ou à expiration (purge chaque minute ; durée de séjour demandée, plafonnée ; morceau jamais téléversé purgé plus tôt) ;
- volume maximal par box (`507`), espace disque minimal ;
- métadonnées dans PostgreSQL (morceaux, destinataires, annuaire), contenu sur disque.

*Client* (`backend/transport`, paquet `iu`) : `IuFileClient` (une requête signée par opération ; reprise d'un téléchargement interrompu par `Range`), `IuFileUploader` (découpage, création de tous les morceaux, téléversement ; état enregistrable après chaque morceau pour reprendre), `IuFileDownloader` (téléchargement, vérification, écriture à sa place, acquittement ; reprise sans retélécharger les morceaux déjà écrits), `Retry` (nouvelles tentatives sur coupure, relais surchargé ou en panne, morceau pas encore prêt ; pas sur un refus définitif), et un outil d'essai en ligne de commande (`IuFileTool envoyer|recevoir`).

*Vérifié en réel* (pile Docker, relais sur PostgreSQL, annuaire de la campagne, novembre 2026), avec une box d'essai enrôlée et un fichier synthétique de **2 Go** (512 morceaux de 4 Mio) :
- **envoi** : le relais est redémarré après 100 morceaux, puis le client est tué après environ 300 ; relancé, il reprend à 370 morceaux sur 512 (état enregistré) et termine ;
- **réception** : même scénario (relais redémarré, client tué, reprise à 303 sur 512) ; fichier reçu **identique** (même SHA-256, comparaison octet par octet) ;
- après les acquittements, le relais est **vide** (0 fichier, 0 ligne) ;
- **box révoquée** dans l'annuaire : son dépôt est refusé, « HTTP 403 — Box non active dans l'annuaire (REVOKED) ».

À ce stade, le relais parle HTTP : en production, il est placé derrière un proxy TLS. Il sert une seule organisation (une clé de responsable) ; la limite de débit par box (§ 6.4) reste à ajouter, seul le volume l'est.

**Étape 3 — files de messages IU-MSG (fait).**

*Relais* : API du § 8.2 (création, dépôt, relève en attente longue, acquittement, renouvellement), tables `queue` et `queue_message` (migration `V2`) :
- création et dépôt réservés aux box enrôlées et actives, comme pour IU-FILE ; relève, acquittement et renouvellement signés avec la clé de la file (Ed25519), anti-rejeu ;
- le `recvId` ne sert qu'à relever et le `sendId` qu'à déposer : l'un ne se déduit pas de l'autre, et un `sendId` présenté à la relève est inconnu ;
- enveloppe de 72 Kio au plus (64 Kio de contenu, plus en-tête et étiquette ; au-delà `413`), 10 000 messages en attente par file et 4 files par box (`507`, réglable par `IMAGUNITY_RELAY_MAX_QUEUES_PER_BOX`), 20 messages par relève ;
- renouvellement : nouveaux identifiants, messages en attente conservés, anciens identifiants aussitôt inconnus ;
- messages expirés purgés avec les morceaux (même durée de séjour par défaut).

*Client* (`backend/transport`, paquet `iu`) : `IuMsgClient` (une requête signée par opération), `QueueAddress` (adresse publiable `<relais>/iu/v1/queues/<sendId>`), `IuMessenger` (signe, chiffre pour le destinataire et dépose, avec nouvelles tentatives), `IuMailbox` (relève en continu sur un fil virtuel ; chaque message est ouvert, sa signature vérifiée d'après l'annuaire, traité, puis acquitté). Règles de la relève :
- message traité puis acquitté : si le traitement échoue, il reste sur le relais et revient à la relève suivante ;
- émetteur absent de l'annuaire local (annuaire pas encore à jour) : message laissé sur le relais, sans acquittement ;
- message illisible, destiné à une autre box ou mal signé : écarté et acquitté, pour ne pas bloquer la file ;
- message déjà traité (même identifiant, rejeu ou acquittement perdu) : acquitté sans être traité une seconde fois (mémoire de 8 jours).

L'outil d'essai gagne `file-creer`, `message-envoyer` et `message-relever`.

*Vérifié en réel* (pile Docker, relais sur PostgreSQL, novembre 2026), avec deux box d'essai X et Y nouvellement enrôlées avec leurs clés de chiffrement :
- Y ouvre sa file puis s'éteint ; X lui dépose **3 messages** ; en base, 3 messages en attente et aucun texte clair ;
- le relais **et sa base** sont redémarrés : les 3 messages sont toujours là ;
- Y se rallume et relève : 3 messages reçus dans l'ordre, signatures vérifiées d'après l'annuaire ; la file est ensuite vide en base, et une seconde relève ne rend rien ;
- **attente longue** : Y relève avec 25 s d'attente, X dépose 6 s plus tard ; le message est remis environ 0,6 s après son dépôt ;
- une box **absente de l'annuaire** est refusée au dépôt : « HTTP 403 — Box absente de l'annuaire de l'organisation ».

Les box n'utilisent pas encore IU-MSG : l'adaptateur `iu` du `TransportPort`, la publication de l'adresse de la file dans l'annuaire et l'appairage par l'annuaire sont l'étape 4.

**Étape 4 — adaptateur `iu` du `TransportPort` (fait).** `IuTransport` (`backend/transport`, paquet `iu`), choisi par `IMAGUNITY_IU_RELAY` (à l'étape 4, aussi `IMAGUNITY_IU_ENABLED=true`) ; il exige l'annuaire. L'orchestrateur, le DICOM, l'empaquetage et la console ne changent pas.

| `TransportPort` | Mise en œuvre |
|---|---|
| `contactAddress()` | Adresse de la file de la box (créée au premier démarrage, ou réservée tant que la box n'est pas enrôlée, § 9) ; `addressKind()` vaut `iu`, la clé du champ `transport` de l'entrée |
| `acceptInvitation(adresse)` | Appairage par l'annuaire : la box qui publie cette adresse devient un contact et reçoit un message `iu.contact` ; en face, le premier message signé d'une box active de l'annuaire crée le contact (`PeerConnected`), que la poignée de main existante lie à sa box |
| `sendMessage` | Message `iu.text`, signé, chiffré pour le destinataire, enregistré dans une **boîte d'envoi** puis déposé en arrière-plan |
| `sendFile` | Téléversement des morceaux (4 Mio, 8 Mio au-delà de 1 Gio), puis description envoyée par messages `iu.file`, **en fragments** de 20 000 caractères si elle dépasse la taille d'un message ; `FileSent` ensuite |
| `receiveFile` | Téléchargement, vérification, réassemblage ; `FileReceived` |
| `discardSentFile` | Suppression des morceaux sur le relais (après l'accusé) |
| `catchUp()` | Rejoue les fichiers proposés mais pas encore acceptés, reçus ou envoyés |

Précisions :
- **Boîte d'envoi** : un relais injoignable ne bloque pas l'orchestrateur. L'ordre est conservé par destinataire, l'adresse relue dans l'annuaire à chaque essai ; un message mal formé ou trop long (refus définitif) est abandonné, un message jamais déposé l'est après 8 jours.
- **Reprise** : l'état de chaque envoi et réception est enregistré à chaque morceau dans le dossier de données de la box (`/data/iu` : file, contacts, messages à déposer, transferts en cours), et non dans sa base, pour garder le transport indépendant du modèle de données. Après un redémarrage, les transferts reprennent où ils en étaient. L'état d'un transfert terminé est oublié après 8 jours.
- **Sécurité** : les morceaux ne sont téléchargés que depuis le relais configuré (une description qui désigne un autre serveur est écartée) ; les messages d'une box absente ou non active de l'annuaire sont écartés ; deux transferts de fichiers à la fois.
- **Bascule depuis SimpleX** : les identifiants locaux IU-T commencent à 1 000 000, loin de ceux de SimpleX. Au premier démarrage sur IU-T, les partenaires déjà liés à une box de l'annuaire sont repris sous leur nom (les routes DICOM qui les désignent restent valables) ; les contacts jamais vérifiés sont oubliés. Les entrées doivent être réémises avec l'adresse IU de chaque box. La bascule se fait sans transfert en cours.
- Défaut corrigé au passage : les fils de relève (`IuMailbox`, étape 3) et d'envoi pouvaient s'arrêter dès leur lancement, faute d'indicateur posé avant le démarrage du fil.

*Vérifié en réel* (pile Docker de la campagne, box A et B en version signée 0.6.3, relais sur PostgreSQL, novembre 2026) :

| Essai | Résultat |
|---|---|
| Bascule de deux box appairées par SimpleX | Partenaires repris sous leur nom, entrées réémises avec l'adresse IU |
| Nominal, 100 instances (50 Mo) | ACKNOWLEDGED en 12,2 s, **100/100** identiques |
| Sans aucun conteneur SimpleX (clients simplex-chat, relais SMP et XFTP arrêtés) | ACKNOWLEDGED en 10,3 s, **100/100** |
| Box destinataire éteinte pendant l'envoi, rallumée 30 s après l'annonce | Restituée 11 s après son redémarrage, **100/100** |
| 600 instances (300 Mo), relais et sa base redémarrés pendant le téléversement, relais redémarré pendant le téléchargement | ACKNOWLEDGED en 51 s, **600/600** |
| Relais après les accusés | 0 morceau en base, 0 fichier sur disque, 0 message en attente |
| Box B réinstallée (nouvelle identité, TPM neuf) : demande d'enrôlement avec l'adresse réservée, enrôlement, appairage depuis la console de A | File créée avec l'identifiant réservé dès l'entrée publiée ; appairage et poignée de main en 1 s ; transfert **100/100** en 12,0 s |

**Étape 5 — bascule (fait).**

*Campagne de la phase 8 sur IU-T* ([rapport, § 7](phase8-campagne.md#7-campagne-rejouée-sur-iu-t)), pile sans aucun conteneur SimpleX, box en version signée 0.7.0 :

| Scénario | Accusé IU-T | SimpleX (phase 8) | Vérification |
|---|---|---|---|
| Nominal, 50 Mo | 12,7 s | 10,2 s | 100/100 |
| Pare-feu (tout entrant bloqué), scan de ports | 10,3 s ; aucun port ouvert sur les box | 10,7 s | 100/100 |
| Liaison bridée à 20 Mbit/s, 100 Mo | 103,9 s | 101,1 s | 200/200 |
| Coupure de 60 s pendant le dépôt, 300 Mo | **95,9 s** (annonce 9,6 s après le rétablissement) | 143,4 s | 600/600 |
| Destinataire éteint ; inspection du relais | restitution 11,1 s après le rallumage ; aucune donnée en clair dans 1 329 fichiers du relais | 8,1 s | 100/100 |
| 2 Go, 4 paquets | **92,0 s** | 157,4 s | 1 024/1 024 |

Résultats au moins équivalents à SimpleX, nettement meilleurs sur la coupure et le gros examen ; le nominal (+2,5 s) reste dans le bruit d'une mesure unique. Constat à traiter à l'étape 6 : le remplissage par des zéros du dernier morceau révèle au relais la taille exacte du paquet chiffré.

*Compose sans SimpleX* : le Compose de base comprend le relais IU-T et sa base, l'annuaire (plus de profil : il est requis) et les box sur IU-T ; plus de `simplex-chat`, ni de relais SMP et XFTP. Les surcouches `compose.annuaire.yml`, `compose.iu.yml`, `compose.iu-box.yml`, `compose.iu-campagne.yml` et `compose.sans-ipv6.yml` sont intégrées ou retirées.

*Retrait de SimpleX du code* : adaptateur `simplex` (client WebSocket, commandes, événements) et dépendance `Java-WebSocket` retirés ; plus de lien d'invitation ni de demande de contact : `TransportPort.connect(adresse)` appaire la box de l'annuaire qui publie cette adresse, `PairingService` suppose l'annuaire, l'API `/api/v1/peers` ne fait plus que lister. Sans relais configuré, le transport est désactivé (les transferts échouent, la box démarre). La reprise des partenaires d'un parc SimpleX (§ étape 4) reste en place pour les box à basculer.

**Étape 6 — performances (en cours).** Fait : annonce anticipée et remplissage aléatoire (v0.5), taille de morceau adaptée à la liaison (v0.6).

- **Annonce anticipée** (§ 6.6), mode `early` par défaut : description envoyée dès la création des morceaux ; téléchargement en attente longue au fil du téléversement ; abandon signalé au destinataire par le retrait des morceaux (`404`).
- **Remplissage aléatoire** du dernier morceau (§ 5.2), au lieu de zéros.

*Mesuré* sur la pile de la campagne (box 0.8.0, mêmes scénarios que l'étape 5, [résultats bruts](../deploy/campagne/resultats/campagne-20261112-201012.json)) :

| Scénario | Étape 5 (mode `complete`) | Étape 6 (mode `early`) | Gain |
|---|---|---|---|
| Liaison bridée à 20 Mbit/s, 100 Mo | 103,9 s | **67,7 s** (restitution 5,3 s après la fin du téléversement) | **−35 %** |
| Examen de 2 Go, 4 paquets | 92,0 s | **79,8 s** | −13 % |
| Coupure de 60 s pendant le dépôt, 300 Mo | 95,9 s | 95,1 s | (durée dominée par la coupure) |
| Nominal, pare-feu, destinataire éteint | 12,7 / 10,3 / 108,3 s | 12,7 / 10,2 / 108,2 s | inchangés |
| Entropie des morceaux sur le relais (minimum) | 5,05 bits/octet (dernier morceau, zéros) | **8,0** | taille exacte du paquet masquée |

Toutes les instances identiques ; aucune donnée en clair sur le relais.

Fait ensuite : **taille de morceau adaptée à la liaison** (v0.6, § 5.2). *Mesuré* sur une liaison bridée à **2 Mbit/s** (sortie de chaque box et du relais), étude de 20 Mo (40 instances), box 0.9.0, box A sans estimation de départ (comme sur un site lent) puis un transfert de rodage ([morceaux de 4 Mio](../deploy/campagne/resultats/campagne-20261112-203055.json), [adaptatifs](../deploy/campagne/resultats/campagne-20261112-203743.json)) :

| Scénario | Morceaux fixes de 4 Mio | Adaptatifs (256 Kio choisis, débit estimé ≈ 115 Kio/s) | Gain |
|---|---|---|---|
| Liaison lente | 134,7 s ; restitution **19,0 s** après la fin du téléversement | **105,6 s** ; restitution **1,8 s** après | **−22 %** |
| Coupure de 30 s pendant le téléversement | 221,5 s | **166,3 s** | **−25 %** |

Une part du gain vient aussi du remplissage : avec 4 Mio, le dernier morceau d'un paquet de 20 Mo téléverse jusqu'à 4 Mio de remplissage. 40/40 instances identiques dans tous les cas. Limite : la moyenne glissante pondère chaque mesure à 0,3 ; une box qui change de liaison (de rapide à lente) s'adapte en quelques envois.

Fait ensuite : **priorité aux séries utiles au diagnostic** (priorité 6 du plan), avec annonce du transfert dès les paquets confiés et restitution au fil de l'eau : voir [priorite-dicom.md](priorite-dicom.md). Sur liaison à 20 Mbit/s, une série prioritaire arrivée en dernier est complète chez le destinataire en 13,3 s au lieu de 61,0 s, l'étude entière restant à 61 s.

Reste, à décider : redondance entre plusieurs relais.

---

## Historique

| Version | Date | Changements |
|---|---|---|
| 0.1 | 2026-11-11 | Premier brouillon. |
| 0.2 | 2026-11-11 | Annonce anticipée (§ 6.6) : téléchargement en parallèle du téléversement, réponse `425 Too Early` et attente longue sur les morceaux, champ `mode` de la description, message `file.cancel`, déroulé du § 10 mis à jour. Motivée par les mesures de la phase 1. |
| 0.3 | 2026-11-12 | Décisions pour l'implémentation : appui sur les briques réalisées (annuaire signé, chiffrement applicatif, messages signés, TPM) ; **pas de seconde couche de chiffrement** des morceaux (paquets déjà chiffrés pour le destinataire, § 5) ; **dépôt authentifié par la signature d'identité de la box**, vérifiée par le relais d'après l'annuaire, au lieu de certificats clients (§ 6.4) ; AES-256-GCM au lieu de ChaCha20-Poly1305 (§ 4.1) ; **annonce anticipée reportée** à l'étape 6 (§ 6.6) ; adresse de la file dans le champ `transport` de l'entrée d'annuaire et appairage par l'annuaire (§ 9) ; JSON signé sur ses octets exacts (§ 7) ; correspondance avec le `TransportPort` (§ 13.2) ; métadonnée « couple émetteur / file » rendue explicite (§ 12, question 5) ; SimpleX gardé en parallèle jusqu'à la bascule. |
| 0.4 | 2026-11-12 | Étape 4 : identifiant de dépôt **réservé par la box** et publié dans sa demande d'enrôlement, accepté par le relais seulement s'il figure dans l'entrée signée de cette box (§ 8.2, § 9) — la création de file exigeant une box déjà enrôlée, la v0.3 ne permettait pas de publier l'adresse à l'enrôlement ; adaptateur `iu`, boîte d'envoi, description en fragments, reprise et bascule depuis SimpleX (§ 13.4). |
| 0.5 | 2026-11-12 | Étape 6 (première partie) : **annonce anticipée** par défaut (§ 6.6 : attente jusqu'à l'expiration, abandon par retrait des morceaux, sans message `file.cancel`), **remplissage pseudo-aléatoire** du dernier morceau (§ 5.2), mode de la description (§ 7) ; mesures (§ 13.4). |
| 0.6 | 2026-11-12 | Étape 6 : **taille de morceau adaptée à la liaison** (§ 5.2 : 3 s par morceau au débit mesuré, 512 morceaux au plus par paquet) ; mesures sur liaison à 2 Mbit/s (§ 13.4). |
