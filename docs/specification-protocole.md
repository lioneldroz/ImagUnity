# ImagUnity — Spécification du protocole de transport (IU-T)

> **Statut : brouillon v0.1, pour décision.** Rien n'est implémenté.
> Objet : définir un transport propre à ImagUnity, inspiré de XFTP et SMP, pour remplacer à terme SimpleX derrière le `TransportPort`.
> Références : [note d'architecture](ImagUnity%20—%20Note%20d'architecture.docx), [plan de réalisation](plan-de-realisation.md), [phase 1](phase1-simplex.md).

---

## 1. Objectifs et périmètre

### 1.1 Pourquoi un protocole propre

| Motivation | Détail |
|---|---|
| Maîtrise du code | Code Java propriétaire, modifiable sans obligation de publication (le client et les serveurs SimpleX sont sous AGPL-3.0, en Haskell). |
| Adapté à l'imagerie | Pas de limite de taille de fichier, reprise au morceau près, découpage et priorité par série DICOM, progression fine pour la console. |
| Réseau fermé | Les box sont enrôlées et connues de l'annuaire : l'anonymat face à des inconnus, au cœur de SimpleX, n'est pas nécessaire. Le protocole peut être plus simple. |
| Une seule pile | Plus de processus externe ni d'API WebSocket locale non authentifiée. |

### 1.2 Ce que le protocole conserve de la note d'architecture

1. **Connexions sortantes uniquement** : les box n'écoutent sur aucun port accessible depuis Internet ; tout passe par HTTPS sortant (port 443).
2. **Poste restante** : émetteur et destinataire n'ont pas besoin d'être connectés en même temps.
3. **Relais aveugle** : il stocke des morceaux chiffrés sans en avoir les clés, et ne sait pas quelle box retire quoi.
4. **Autorisation par capacité** : retirer un morceau exige un identifiant opaque et une signature avec la clé de retrait correspondante, pas une identité.
5. **Durée de séjour courte et bornée**, avec purge après retrait ou expiration (argument « transit, pas hébergement » vis-à-vis de l'HDS).
6. **Habilitation décidée chez l'émetteur**, d'après l'annuaire.

### 1.3 Hors périmètre de ce document

- La gouvernance détaillée de l'annuaire (format complet, procédure d'enrôlement, interface d'administration) : spécification séparée. Seul ce dont le protocole a besoin est décrit au § 9.
- Le format interne du paquet d'examen (manifeste, archive, compression) : il relève du module `packaging`. Le protocole transporte un fichier opaque.

### 1.4 Règle absolue : aucune cryptographie maison

Le protocole **assemble** des primitives standard et éprouvées ; il n'en invente aucune. Toute construction s'appuie sur une référence publiée (RFC, norme NIST, schéma de TLS 1.3). Un **audit de sécurité externe** est un prérequis à la mise en production.

---

## 2. Vue d'ensemble

```
 Box émettrice                     Relais                         Box réceptrice
┌──────────────┐  HTTPS sortant  ┌──────────────────────┐  HTTPS sortant  ┌──────────────┐
│ paquet       │ ──────────────► │ IU-FILE  morceaux    │ ◄────────────── │ téléchargement│
│ chiffrement  │                 │          chiffrés    │                 │ vérification  │
│ découpage    │ ──────────────► │ IU-MSG   files de    │ ◄────────────── │ relève (long  │
│              │   annonce       │          messages    │   polling)      │ polling)      │
└──────┬───────┘                 └──────────────────────┘                 └──────┬───────┘
       │ clé publique + file du destinataire                                     │
       └──────────────────────────► Annuaire de l'organisation ◄─────────────────┘
```

Deux services sur le relais, sur le même point d'entrée HTTPS :

- **IU-FILE** (équivalent de XFTP) : stockage temporaire de morceaux chiffrés de taille fixe.
- **IU-MSG** (équivalent de SMP) : files de messages unidirectionnelles pour la signalisation (annonce d'un fichier, accusé de bout en bout).

Un même relais peut offrir les deux services ; une organisation peut en utiliser plusieurs (redondance, proximité réseau).

**Choix de HTTPS (HTTP/2 sur TLS 1.3)** plutôt qu'un protocole binaire dédié : passe tous les pare-feu et proxys qui autorisent le web, outillage standard (répartiteurs de charge, supervision), et implémentation directe en Java des deux côtés.

---

## 3. Acteurs, clés et modèle de menace

### 3.1 Acteurs

| Acteur | Rôle | Fait confiance à |
|---|---|---|
| Box | Émet et reçoit les examens. Porte toute la sécurité. | Sa propre clé, la clé du responsable de son organisation (épinglée à l'enrôlement). |
| Relais | Stocke temporairement morceaux et messages. | Personne : il vérifie seulement des signatures. |
| Annuaire | Publie les entrées signées des box de l'organisation. | — (ses entrées sont signées par le responsable ; l'annuaire lui-même n'est pas de confiance). |
| Responsable de l'organisation | Valide l'enrôlement et la révocation des box. | — |

### 3.2 Clés

| Clé | Type | Où elle naît | Durée de vie | Usage |
|---|---|---|---|---|
| Identité de signature de la box | Ed25519 (+ ML-DSA-65 en hybride, § 4.2) | Dans la box (TPM / élément sécurisé à terme) | Vie de la box | Signer les annonces et les accusés |
| Identité de chiffrement de la box | X25519 + ML-KEM-768 | Dans la box | Vie de la box, renouvelable | Recevoir les clés de fichier |
| Clé du responsable | Ed25519 (+ ML-DSA-65) | Hors ligne, chez le responsable | Longue | Signer les entrées de l'annuaire |
| Clé de fichier `K` | 256 bits aléatoires | Box émettrice | Un transfert | Chiffrer les morceaux |
| Clé d'émetteur de morceau | Ed25519 | Box émettrice | Un morceau | Téléverser, supprimer |
| Clé de retrait de morceau | Ed25519 | Box émettrice, pour chaque destinataire | Un morceau | Télécharger, acquitter |
| Clé de file (réception) | Ed25519 | Box réceptrice | Vie de la file | Relever et acquitter les messages |
| Certificat client d'enrôlement | X.509 / Ed25519 | Box, signé par l'organisation | Renouvelable | Réserver le **dépôt** aux box enrôlées (§ 6.4) |

Remplacement plutôt que restauration (note d'architecture) : aucune clé de box n'est sauvegardée. Une box en panne est remplacée et l'ancienne révoquée.

### 3.3 Modèle de menace

| Adversaire | Capacités supposées | Ce que le protocole garantit | Ce qu'il ne garantit pas |
|---|---|---|---|
| Observateur réseau passif | Voit tout le trafic chiffré | Confidentialité du contenu et des identités (TLS + chiffrement de bout en bout) | Les métadonnées : qui parle à quel relais, quand, quels volumes |
| Attaquant réseau actif | Intercepte, modifie, rejoue | Intégrité (AEAD, signatures, empreintes), anti-rejeu | Disponibilité (il peut couper la liaison) |
| Opérateur de relais curieux ou compromis | Lit son stockage et ses journaux | Ne peut lire aucun morceau ni message ; ne peut pas relier un dépôt à un retrait par les identifiants | Il voit les volumes, les horaires et les adresses IP des box ; il peut supprimer ou retenir des données (déni de service) |
| Box tierce de l'organisation | Enrôlée, malveillante | Ne peut ni lire ni retirer ce qui ne lui est pas destiné ; ne peut pas se faire passer pour une autre box (signatures) | Elle peut saturer une file (limites de débit, § 6.4) |
| Box volée | Accès physique | Révocation dans l'annuaire ; clés dans un composant sécurisé | Les paquets déjà émis vers elle restent récupérables jusqu'à expiration (d'où la durée de séjour courte) |
| Annuaire compromis | Modifie les entrées | Les entrées sont signées par le responsable : une substitution de clé est détectée | Il peut retirer des entrées (déni de service) |
| Clé du responsable compromise | Signe de fausses entrées | — | **Point critique** : détournement possible. Parade : clé hors ligne, double signature envisagée |
| Enregistrement aujourd'hui, déchiffrement demain | Stocke le trafic, ordinateur quantique futur | Échange de clés hybride post-quantique (§ 4.2) | — |

---

## 4. Primitives cryptographiques

### 4.1 Suite de base

Toutes les primitives sont disponibles **dans le JDK 25**, sans bibliothèque native.

| Fonction | Primitive | Référence | API Java |
|---|---|---|---|
| Canal box ↔ relais | TLS 1.3 uniquement | RFC 8446 | `javax.net.ssl` |
| Chiffrement authentifié des morceaux et messages | ChaCha20-Poly1305 | RFC 8439 | `Cipher "ChaCha20-Poly1305"` |
| Échange de clés classique | X25519 | RFC 7748 | `KeyAgreement "XDH"` |
| Échange de clés post-quantique | ML-KEM-768 | FIPS 203 | `KEM "ML-KEM"` (JDK 24+) |
| Signature classique | Ed25519 | RFC 8032 | `Signature "Ed25519"` |
| Signature post-quantique | ML-DSA-65 | FIPS 204 | `Signature "ML-DSA"` (JDK 24+) |
| Dérivation de clés | HKDF-SHA-256 | RFC 5869 | `KDF "HKDF-SHA256"` (JDK 25) |
| Empreintes | SHA-256 | FIPS 180-4 | `MessageDigest "SHA-256"` |
| Aléa | `SecureRandom` (DRBG) | NIST SP 800-90A | `SecureRandom.getInstanceStrong()` |

Toute disponibilité d'API ci-dessus est à **vérifier sur le JDK retenu** au début de l'implémentation.

### 4.2 Hybride post-quantique

La note d'architecture demande d'examiner dès la conception les algorithmes résistants au calcul quantique, car des données de santé peuvent être enregistrées aujourd'hui pour être déchiffrées plus tard.

- **Chiffrement de la clé de fichier** : encapsulation hybride X25519 + ML-KEM-768. Les deux secrets partagés sont concaténés puis passés dans HKDF avec le transcript (clés publiques et textes chiffrés d'encapsulation), sur le modèle du groupe hybride `X25519MLKEM768` de TLS 1.3. Le résultat reste sûr tant qu'**au moins une** des deux primitives tient.
- **Signatures** des annonces, accusés et entrées d'annuaire : double signature Ed25519 + ML-DSA-65, les deux devant être valides.
- **Clés de capacité des morceaux** (émetteur, retrait) : Ed25519 seul. Elles ne protègent que l'accès pendant la durée de séjour, pas la confidentialité : une attaque quantique future ne donne rien.
- **Canal TLS** : groupe hybride `X25519MLKEM768` si la pile TLS du JDK le propose ; sinon X25519, la confidentialité de bout en bout ne dépendant pas de TLS.

### 4.3 Agilité cryptographique

Chaque structure porte un identifiant de suite, par exemple `IU1`, qui désigne l'ensemble des algorithmes ci-dessus. Une nouvelle suite (`IU2`) peut être introduite sans casser les box existantes ; une suite compromise peut être refusée par configuration.

---

## 5. Chiffrement et découpage d'un fichier

### 5.1 Préparation

Le module `packaging` produit un **paquet** : un fichier opaque (manifeste, instances DICOM compressées sans perte). La box calcule `D = SHA-256(paquet)`.

### 5.2 Taille des morceaux

- Taille de morceau `S` fixée pour tout le transfert, choisie parmi un **ensemble fermé** : 256 Kio, 1 Mio, 4 Mio (par défaut), 8 Mio.
- Le choix se fait selon la qualité de la liaison (savoir-faire de la box). Un petit morceau perdu coûte peu à renvoyer ; un gros morceau réduit le nombre de requêtes.
- Le dernier morceau est complété (*padding*) jusqu'à `S` : le relais ne voit que des morceaux de tailles standard. Le nombre de morceaux révèle la taille approximative du paquet, ce qui est accepté (les volumes font partie des métadonnées visibles).

### 5.3 Chiffrement des morceaux

Pour un transfert :

1. Tirer `K` (32 octets) et un identifiant de transfert `T` (16 octets), tous deux aléatoires.
2. Découper le paquet en `n` morceaux de `S` octets, en complétant le dernier (longueur réelle conservée dans la description, § 7).
3. Pour le morceau `i` (de 0 à `n-1`) :
   - nonce = `i` codé sur 96 bits (gros-boutiste). Il est unique car `K` n'est utilisé que pour ce transfert ;
   - données associées (AAD) = `"IU1-chunk" ‖ T ‖ i ‖ n ‖ S` ;
   - `C_i = ChaCha20-Poly1305(K, nonce, AAD, morceau_i)`.
4. Calculer `H_i = SHA-256(C_i)`, publié dans la description.

L'AAD lie chaque morceau à son transfert, à sa position et au nombre total : un relais ne peut ni réordonner, ni tronquer, ni substituer un morceau d'un autre transfert sans que le déchiffrement échoue.

---

## 6. IU-FILE : stockage des morceaux sur le relais

### 6.1 Principe de capacité

Pour chaque morceau, la box émettrice génère :

- une paire de clés **émetteur** `(sk_snd, pk_snd)` ;
- pour **chaque destinataire** et chaque relais qui héberge le morceau, une paire de clés de **retrait** `(sk_rcv, pk_rcv)`.

Le relais ne reçoit que les clés publiques et attribue des **identifiants opaques** aléatoires (32 octets) : un `senderId`, et un `recipientId` par clé de retrait. Les clés privées de retrait voyagent jusqu'au destinataire dans la description de fichier, chiffrée de bout en bout. Le relais ne peut donc pas relier un `senderId` à un `recipientId` par leur forme, ni savoir qui retire.

### 6.2 API

Toutes les requêtes passent par TLS 1.3. Les requêtes authentifiées portent un en-tête de signature (§ 6.3).

| Opération | Requête | Authentification | Réponse |
|---|---|---|---|
| Créer un morceau | `POST /iu/v1/chunks` corps : `{suite, size, digest, senderKey, recipientKeys[], ttl}` | Certificat client d'enrôlement (§ 6.4) | `{senderId, recipientIds[]}` |
| Téléverser | `PUT /iu/v1/chunks/{senderId}` corps : `C_i` | Signature `sk_snd` | `204` ; le relais vérifie `size` et `digest` |
| Télécharger | `GET /iu/v1/chunks/{recipientId}` (`Range` autorisé) | Signature `sk_rcv` | `200` + `C_i` |
| Acquitter | `POST /iu/v1/chunks/{recipientId}/ack` | Signature `sk_rcv` | `204` ; accès révoqué pour ce destinataire |
| Supprimer | `DELETE /iu/v1/chunks/{senderId}` | Signature `sk_snd` | `204` |
| État | `GET /iu/v1/chunks/{senderId}` | Signature `sk_snd` | `{uploaded, recipientsAcked}` (sans identifier les destinataires) |

Règles du relais :

- un morceau est **supprimé** quand tous ses destinataires l'ont acquitté, à la demande de l'émetteur, ou à l'expiration de son `ttl` ;
- `ttl` est plafonné par la configuration du relais (proposition : 72 h par défaut, 7 jours au plus) ;
- un morceau incomplet (téléversement inachevé) expire au bout d'une durée courte (proposition : 24 h) ;
- le relais ne journalise aucun identifiant de morceau associé à une adresse IP au-delà de ce qu'exige la lutte contre les abus.

### 6.3 Signature des requêtes

En-têtes `IU-Key-Id` (identifiant du morceau ou de la file), `IU-Timestamp` (UTC, secondes), `IU-Request-Id` (16 octets aléatoires) et `IU-Signature`.

La signature Ed25519 porte sur la chaîne canonique :

```
"IU1-req" ‖ méthode ‖ chemin ‖ IU-Timestamp ‖ IU-Request-Id ‖ SHA-256(corps)
```

Le relais refuse une requête dont l'horodatage s'écarte de plus de 5 minutes, ou dont le `IU-Request-Id` a déjà été vu dans cette fenêtre (anti-rejeu). Une variante par défi (le relais fournit un nonce) est à étudier si l'horloge des box n'est pas fiable.

### 6.4 Réserver le dépôt aux box enrôlées

La note d'architecture distingue :

- **Dépôt** (création de morceau, création de file, dépôt de message) : réservé aux box enrôlées, pour que le relais ne serve pas à n'importe qui. Authentification par **certificat client TLS** délivré à l'enrôlement par l'organisation ; le relais vérifie seulement qu'il est signé par une organisation qu'il sert.
- **Retrait** : **aucun contrôle d'identité**, uniquement la capacité (identifiant + signature). Un contrôle d'identité au retrait obligerait le relais à savoir qui récupère quoi, ce que la note proscrit.

Le relais applique des limites de débit et de volume par certificat (protection contre la saturation par une box malveillante).

### 6.5 Reprise et redondance

- **Reprise** : l'unité de reprise est le morceau. La box enregistre en base l'état de chaque morceau (créé, téléversé, annoncé) et reprend après une coupure sans recommencer le transfert. `Range` permet aussi de reprendre un téléchargement en cours de morceau.
- **Redondance** : un morceau peut être déposé sur plusieurs relais ; la description liste toutes les répliques et le destinataire prend la première disponible.
- **Ordre et parallélisme** : libres côté box (savoir-faire propriétaire) ; par exemple les séries utiles au diagnostic d'abord.

---

## 7. Description de fichier

La description donne au destinataire tout ce qu'il faut pour retrouver, vérifier et déchiffrer le paquet.

```json
{
  "suite": "IU1",
  "transferId": "<T, base64url>",
  "chunkSize": 4194304,
  "chunkCount": 312,
  "size": 1306525184,
  "digest": "<D = SHA-256(paquet), base64url>",
  "fileKey": "<K, base64url>",
  "content": { "type": "dicom-study", "manifestDigest": "<…>" },
  "chunks": [
    {
      "index": 0,
      "digest": "<H_0>",
      "replicas": [
        { "relay": "https://relais-1.org.example", "recipientId": "<…>", "retrievalKey": "<sk_rcv, base64url>" }
      ]
    }
  ],
  "expiresAt": "2026-11-14T13:00:00Z"
}
```

- Encodage : JSON canonique (RFC 8785) pour que les signatures soient reproductibles. Un encodage binaire (CBOR déterministe, RFC 8949) reste une option si la taille devient un problème.
- Taille : environ 200 octets par morceau et par réplique. Un paquet de 4 Gio en morceaux de 4 Mio donne environ 200 Kio de description. Au-delà de la taille maximale d'un message IU-MSG (§ 8.3), la description est elle-même déposée comme un fichier IU-FILE, et le message ne contient qu'une **description de redirection** (même mécanisme que XFTP).

La description n'est jamais envoyée en clair : elle est placée dans un message chiffré et signé (§ 8.4).

---

## 8. IU-MSG : files de signalisation

### 8.1 Principe

Chaque box crée une **file de réception** sur un relais de son organisation. La file a deux identifiants opaques :

- `sendId` : permet de **déposer** un message ; publié dans l'entrée d'annuaire de la box ;
- `recvId` : permet de **relever** les messages ; connu de la box seule, protégé par la clé de file.

La box relève sa file par connexions sortantes (attente longue, *long polling*). Aucune connexion entrante n'est nécessaire.

### 8.2 API

| Opération | Requête | Authentification | Réponse |
|---|---|---|---|
| Créer une file | `POST /iu/v1/queues` corps : `{suite, queueKey}` | Certificat client d'enrôlement | `{sendId, recvId}` |
| Déposer | `POST /iu/v1/queues/{sendId}/messages` corps : enveloppe (§ 8.4) | Certificat client d'enrôlement | `202 {messageId}` |
| Relever | `GET /iu/v1/queues/{recvId}/messages?wait=30` | Signature clé de file | `200 [messages]` ou `204` après attente |
| Acquitter | `DELETE /iu/v1/queues/{recvId}/messages/{messageId}` | Signature clé de file | `204` ; message supprimé |
| Renouveler | `POST /iu/v1/queues/{recvId}/rotate` | Signature clé de file | nouveaux `{sendId, recvId}` |

Les messages non relevés expirent selon la même politique que les morceaux.

### 8.3 Taille des messages

Taille maximale : **64 Kio** par message (contre environ 16 Ko pour SMP). Les messages sont complétés jusqu'à des tailles par paliers (4, 16, 64 Kio) pour limiter ce que leur taille révèle.

### 8.4 Enveloppe et types de messages

Une enveloppe déposée contient :

```
suite ‖ encapsulation hybride (X25519 éphémère + ML-KEM-768) vers le destinataire
      ‖ ChaCha20-Poly1305(clé dérivée, contenu signé)
```

Le **contenu signé** comprend l'identifiant de la box émettrice, un identifiant de message unique, un horodatage, le type et le corps, signés Ed25519 + ML-DSA-65 par l'identité de l'émetteur. Le destinataire vérifie la signature d'après l'annuaire et rejette les messages déjà vus (anti-rejeu par identifiant de message).

Le relais ne voit ni l'émetteur, ni le type, ni le contenu.

| Type | Sens | Corps |
|---|---|---|
| `file.announce` | émetteur → destinataire | description de fichier (§ 7) ou description de redirection |
| `file.ack` | destinataire → émetteur | `transferId`, résultat : `complete` / `failed`, nombre d'instances vérifiées, empreinte du manifeste (§ 10) |
| `file.reject` | destinataire → émetteur | `transferId`, motif (expéditeur non appairé, examen invalide…) |
| `ping` | les deux | diagnostic de liaison |

---

## 9. Ce que le protocole attend de l'annuaire

Une **entrée de box**, signée par le responsable de l'organisation :

```json
{
  "boxId": "<identifiant stable>",
  "organization": "<identifiant de l'organisation>",
  "displayName": "CHU de … — service d'imagerie",
  "signingKeys": { "ed25519": "<…>", "mlDsa65": "<…>" },
  "encryptionKeys": { "x25519": "<…>", "mlKem768": "<…>" },
  "inbox": { "relay": "https://relais-1.org.example", "sendId": "<…>" },
  "validFrom": "…", "validUntil": "…",
  "revoked": false
}
```

- La box **épingle** la clé du responsable à l'enrôlement, après vérification de son empreinte hors bande (QR code ou code court, comme prévu par la note).
- Avant tout envoi, la box émettrice vérifie la signature de l'entrée du destinataire et qu'elle n'est ni expirée ni révoquée.
- Une révocation empêche tout nouvel envoi ; les paquets déjà émis restent récupérables jusqu'à leur expiration.

---

## 10. Déroulé complet d'un envoi

1. Le PACS envoie l'examen à la box (C-STORE) ; la box identifie le destinataire (AET appelé).
2. La box lit l'entrée du destinataire dans l'annuaire et vérifie sa signature.
3. Elle produit le paquet, choisit `S`, chiffre et découpe (§ 5).
4. Pour chaque morceau : création sur un ou plusieurs relais, puis téléversement (§ 6). État enregistré morceau par morceau.
5. Elle construit la description (§ 7), la chiffre et la signe pour le destinataire, et la dépose dans sa file (§ 8) : état `ANNOUNCED`.
6. La box réceptrice relève le message, vérifie la signature de l'émetteur et qu'il appartient à son organisation.
7. Elle télécharge les morceaux, vérifie chaque `H_i`, déchiffre, contrôle `D`, et acquitte chaque morceau auprès du relais.
8. Elle dépaquette, vérifie l'empreinte de chaque instance DICOM, pousse l'examen vers le mini-PACS.
9. Elle renvoie un `file.ack` signé : l'émetteur passe le transfert à `ACKNOWLEDGED`.

Les étapes 4 et 7 reprennent là où elles s'étaient arrêtées après une coupure.

---

## 11. Comparaison avec XFTP et SMP

| Aspect | XFTP / SMP (SimpleX) | IU-T |
|---|---|---|
| Langage, licence | Haskell, AGPL-3.0 | Java, propriétaire (spécification publiable) |
| Transport | TLS, protocole binaire dédié | HTTPS (HTTP/2, TLS 1.3) |
| Identités | Aucune, contacts anonymes | Box enrôlées, annuaire signé |
| Dépôt | Ouvert ou protégé par mot de passe | Certificat client d'enrôlement |
| Retrait | Capacité (identifiant + signature) | Identique |
| Taille de fichier | Limitée (1 Go en v5.0.0) | Non limitée par le protocole |
| Taille de morceau | Quelques tailles fixes | Ensemble fermé, choisi selon la liaison |
| Reprise | Par morceau | Par morceau, plus `Range` dans un morceau |
| Post-quantique | En cours d'intégration (messages) | Hybride dès la v1 pour les clés de fichier et les signatures |
| Double ratchet, confidentialité persistante | Oui (messages) | Non : clé éphémère par transfert et par message (§ 12) |
| Métadonnées | Minimisées (pas d'identité) | Le relais voit les adresses IP des box enrôlées ; le contenu et le couple émetteur / destinataire restent cachés |

---

## 12. Questions ouvertes

1. **Confidentialité persistante des messages** : l'encapsulation éphémère par message protège chaque message isolément, mais la compromission de la clé d'identité de chiffrement d'une box exposerait les descriptions encore stockées. Faut-il une rotation fréquente des clés de chiffrement (publiée dans l'annuaire) ou un ratchet ?
2. **Horloge des box** : l'anti-rejeu par horodatage suppose une horloge juste. Les sites éloignés ont-ils un NTP fiable, ou faut-il la variante par défi ?
3. **Taille des descriptions** : JSON canonique suffit-il, ou faut-il CBOR dès la v1 ?
4. **Opérateur des relais** : organisation, région, ou établissements (question déjà ouverte dans la note). La spécification permet les trois.
5. **Métadonnées** : faut-il masquer davantage le trafic (envois de remplissage, horaires lissés) ? Coût en bande passante sur les liaisons lentes.
6. **Base du relais** : le relais IU-T a besoin d'un stockage de métadonnées (morceaux, clés publiques, files, expirations) partagé par plusieurs box et consulté en concurrence. **PostgreSQL** y est adapté, alors qu'il n'avait pas de place sur les relais SimpleX. Les données des morceaux restent sur disque ou dans un stockage objet.
7. **Interopérabilité SimpleX** : faut-il garder l'adaptateur SimpleX en parallèle pendant une période de transition ?

---

## 13. Mise en œuvre proposée

### 13.1 Modules

| Module | Contenu |
|---|---|
| `backend/protocol` (nouveau) | Formats (description, enveloppe, requêtes signées), suite `IU1`, primitives cryptographiques. Partagé par la box et le relais. Couvert par des vecteurs de test. |
| `backend/transport` | Nouvel adaptateur `iu` qui implémente `TransportPort`, à côté de `simplex`. |
| `relay/` (nouvelle application) | Relais IU-FILE + IU-MSG : Spring Boot, PostgreSQL pour les métadonnées, disque pour les morceaux. |

### 13.2 Étapes

| Étape | Contenu | Estimation |
|---|---|---|
| 1 | Revue et validation de cette spécification ; modèle de menace détaillé | 1 à 2 semaines |
| 2 | Module `protocol` : primitives, formats, vecteurs de test | 2 à 3 semaines |
| 3 | Relais IU-FILE + client, reprise par morceau | 3 à 4 semaines |
| 4 | Relais IU-MSG + client, enveloppes signées | 2 à 3 semaines |
| 5 | Adaptateur `iu` du `TransportPort` ; scénarios de la phase 8 sur IU-T et SimpleX en parallèle | 2 semaines |
| 6 | Audit de sécurité externe et corrections | selon l'auditeur |

**Total indicatif : 2,5 à 3,5 mois pour une personne, hors audit.**

Calendrier recommandé : mener la preuve de concept sur SimpleX (phases 1 à 8), valider cette spécification en parallèle, puis implémenter IU-T pour le pilote. Le `TransportPort` permet de changer de transport sans toucher au DICOM, à l'empaquetage ni à l'orchestrateur.
