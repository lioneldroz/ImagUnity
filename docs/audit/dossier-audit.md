# Dossier d'audit de sécurité — ImagUnity

> Destinataire : le prestataire d'audit externe (étape 7 de la [spécification IU-T](../specification-protocole.md)).
> Objet : tout le nécessaire pour auditer le système sans perdre de temps à le reconstituer : périmètre, architecture, modèle de menace, inventaire cryptographique, surface d'attaque, mise en œuvre d'une pile d'essai, limites connues et constats du pré-audit interne.
> Version du code : branche `main` du dépôt, à la date de la commande (référence du commit à fixer au lancement).

## 1. Le système en une page

ImagUnity transfère des examens d'imagerie médicale (DICOM) entre établissements de santé, sans port entrant sur les sites et sans qu'aucun intermédiaire ne puisse lire les données.

```
 Site A                                   Internet                                   Site B
 PACS A ──DICOM──► box A ──HTTPS sortant──► relais IU-T ◄──HTTPS sortant── box B ──DICOM──► PACS B
                     │                       (aveugle)                        │
                     └──────────── HTTPS sortant ──► service d'annuaire ◄─────┘
                                    (entrées et configurations signées par le responsable, clé hors ligne)
```

- **Box** : appareil installé sur chaque site. Reçoit les études du PACS local (C-STORE, STOW-RS), les empaquette (tar + zstd), les **chiffre pour la box destinataire**, les découpe en morceaux et les dépose sur le relais ; à l'autre bout, télécharge, vérifie, déchiffre, restitue au PACS, renvoie un accusé signé. Console web locale pour les opérateurs.
- **Relais IU-T** : stocke des morceaux chiffrés (IU-FILE) et des files de messages chiffrés (IU-MSG) le temps de leur retrait. N'accepte les dépôts que des box enrôlées et actives de l'annuaire. Peut tourner en plusieurs instances sans état (PostgreSQL + stockage S3), et une box peut utiliser plusieurs relais indépendants.
- **Service d'annuaire** : publie les entrées de box et les documents de configuration centralisée, **signés hors ligne par le responsable de l'organisation** ; le service ne signe rien. Interface d'administration pour préparer la configuration.
- **Responsable** : détient la clé de l'organisation (hors ligne), enrôle et révoque les box, signe la configuration.
- **Éditeur** : signe les versions du logiciel de la box (mises à jour signées).

Documents de référence (tous dans `docs/`) :

| Sujet | Document |
|---|---|
| Protocole de transport IU-T (relais, morceaux, files, plusieurs relais, relais réparti) | [specification-protocole.md](../specification-protocole.md) |
| Annuaire signé, enrôlement, révocation, annonces et accusés signés, protection des clés | [annuaire-signe.md](../annuaire-signe.md) |
| Chiffrement applicatif des paquets | [chiffrement-applicatif.md](../chiffrement-applicatif.md) |
| Clés scellées par le TPM | [cles-tpm.md](../cles-tpm.md) |
| Mises à jour signées | [mises-a-jour-signees.md](../mises-a-jour-signees.md) |
| Configuration centralisée, groupes de box | [configuration-centralisee.md](../configuration-centralisee.md) |
| Entrée DICOM, routage, STOW-RS, console | [phase2-dicom.md](../phase2-dicom.md), [routage-dicom.md](../routage-dicom.md), [stow-rs.md](../stow-rs.md), [phase7-console.md](../phase7-console.md) |
| Campagne d'essais (scénarios réels, résultats bruts) | [phase8-campagne.md](../phase8-campagne.md), `deploy/campagne/` |

## 2. Périmètre de l'audit

**Tout le système** :

1. **Conception cryptographique** : suite `IU1`, construction hybride post-quantique, documents signés, anti-rejeu, anti-retour en arrière, chiffrement applicatif, protection des clés (phrase de passe, TPM), mises à jour signées.
2. **Revue de code ciblée** (Java 25, Spring Boot 4) : modules `protocol`, `directory`, `packaging`, `transport`, `relay`, `orchestration`, `dicom`, `api`, `app`, `directory-server`, `release` ; console React (`frontend/`).
3. **Tests d'intrusion boîte grise** sur une pile déployée (§ 7) : box (console, API, DICOM, STOW-RS), relais (IU-FILE, IU-MSG, répartiteur), service d'annuaire (API, interface d'administration).
4. **Déploiement** : images, Compose, manifestes K3s, chaîne d'intégration (GitHub Actions).

Hors périmètre : le PACS et les postes des sites, l'hébergement physique, les procédures organisationnelles du responsable (à évaluer séparément), les services tiers (S3 du marché, PostgreSQL) au-delà de leur configuration.

## 3. Actifs et objectifs de sécurité

| Actif | Objectif |
|---|---|
| Images et données de patients | Confidentialité de bout en bout (seule la box destinataire déchiffre) ; intégrité (instance restituée identique) ; aucune donnée de patient dans les journaux |
| Clé du responsable | Hors ligne ; seule source de confiance pour l'annuaire et la configuration |
| Clés des box (identité, chiffrement) | Sur la box, chiffrées (phrase de passe ou secret scellé par le TPM) |
| Clé de l'éditeur | Hors ligne ; seule source des versions installables |
| Métadonnées (qui envoie à qui, quand, combien) | Minimisées sur le relais ; écarts assumés documentés (§ 9) |
| Disponibilité des transferts | Reprise après coupure ; redondance des relais ; pas de blocage durable par un relais ou une box défaillants |

## 4. Modèle de menace

Détail : [spécification § 3.3](../specification-protocole.md).

| Adversaire | Capacités supposées | Ce qu'il ne doit pas obtenir |
|---|---|---|
| Réseau (Internet, Wi-Fi d'établissement) | Écoute, modification, rejeu, coupure | Lire ou altérer un examen ; usurper une box ; faire accepter un message rejoué |
| **Relais compromis** ou malveillant (opérateur, intrusion) | Tout ce que voit et stocke le relais ; réponses arbitraires aux box | Lire un examen ; fabriquer ou modifier un examen, une annonce, un accusé ; associer davantage que ce qui est documenté ; bloquer durablement une box qui dispose d'un autre relais |
| **Service d'annuaire compromis** | Servir, retenir ou réordonner des documents ; interface d'administration | Fabriquer une entrée de box ou une configuration ; imposer une version plus ancienne ; faire router des examens hors de l'organisation |
| Box de l'organisation compromise | Ses clés, ses messages signés | Lire les examens destinés à d'autres box ; se faire passer pour une autre box ; perturber les autres au-delà de ses propres échanges |
| Accès au réseau local d'un site | Atteindre la console et l'entrée DICOM de la box | Lire les transferts sans compte d'opérateur ; écrire hors du dossier de réception ; épuiser la box |
| Attaquant de la chaîne logicielle | Dépendance ou version publiée piégée | Faire exécuter par la box un logiciel non signé par l'éditeur ou plus ancien |
| Adversaire quantique futur (« stocker maintenant, déchiffrer plus tard ») | Enregistrement du trafic aujourd'hui | Déchiffrer plus tard (accord de clé hybride ML-KEM-768) |

## 5. Inventaire cryptographique

Toutes les primitives viennent du **JDK 25** (aucune bibliothèque cryptographique tierce, aucune construction maison).

| Usage | Construction | Où dans le code |
|---|---|---|
| Signature d'identité (annuaire, configuration, annonces, accusés, dépôts sur le relais, comptes rendus) | Ed25519 **et** ML-DSA-65, les deux exigées (suite `IU1`), sur les octets exacts du document | `directory/HybridKeyPair`, `HybridSignature`, `SignedDocument` ; `protocol/SignedRequest`, `SignedMessage` |
| Signature de capacité (morceaux, files) | Ed25519, clé par morceau / par file | `protocol/SignedRequest`, `transport/iu/IuFileClient`, `relay/RequestAuthenticator` |
| Accord de clé (paquets, messages) | DHKEM X25519 + ML-KEM-768, combinés par HKDF-SHA256 (contexte `IU1-paquet` + empreinte de l'en-tête) | `directory/HybridKem*`, `packaging` (chiffrement applicatif) |
| Chiffrement | AES-256-GCM ; paquets : blocs de 1 Mio, nonce = rang du bloc, données associées = empreinte de l'en-tête + indicateur de dernier bloc | `packaging` |
| Remplissage du dernier morceau | flux AES-256-CTR d'une clé aléatoire par envoi (masque la taille exacte ; pas un rôle de confidentialité) | `protocol/Chunks` |
| Empreintes | SHA-256 (morceaux, paquets, corps des requêtes signées) | partout |
| Protection des clés au repos | PBKDF2-HMAC-SHA256 600 000 itérations, sel 16 octets ; AES-256-GCM nonce aléatoire 12 octets ; ou secret scellé par le TPM 2.0 (lié aux PCR en option) | `directory/KeyFiles`, `KeyProtection`, `SealedFiles` ; `app/config/Tpm*` |
| Mises à jour | Manifeste signé par la clé de l'éditeur (Ed25519 + ML-DSA-65), numéro de version croissant, empreinte de l'artefact | `release` |
| Anti-rejeu des requêtes au relais | horodatage ± 5 min, identifiant de requête unique dans la fenêtre, en base partagée par les instances | `protocol/ReplayGuard`, `relay/ReplayStore` |
| Anti-retour en arrière | numéros d'émission des entrées, numéros de version des configurations (plancher gardé par la box), numéros de version du logiciel | `directory/EntryVerifier`, `app/config/CentralRules`, `release` |
| Transport | TLS 1.3 vers le relais et l'annuaire (terminaison au proxy en production) ; la confidentialité de bout en bout n'en dépend pas | déploiement |

Points sur lesquels l'avis de l'auditeur est particulièrement attendu :

1. Combinaison hybride des secrets (ordre, contexte HKDF) et liaison au destinataire.
2. Double signature sur les octets exacts : absence d'ambiguïté entre types de documents (`type` présent dans la configuration, champs refusés s'ils sont inconnus).
3. Capacités Ed25519 seules pour l'accès aux morceaux (justification : accès limité à la durée de séjour, données déjà chiffrées).
4. Anti-rejeu partagé en base entre instances du relais ; tolérance d'horloge.
5. Liaison de l'annonce signée (taille, empreinte) au paquet chiffré.

## 6. Surface d'attaque

| Composant | Entrées | Authentification | Code |
|---|---|---|---|
| Box — entrée DICOM | C-STORE (port 11112), sur le réseau du site | AET appelant autorisé (liste) | `dicom/DicomStoreScp` |
| Box — STOW-RS | `POST /dicomweb/{aet}/studies[/{study}]` | compte d'opérateur | `api`, `dicom/StowService` |
| Box — console et API | `/api/v1/**`, fichiers de la console | compte d'opérateur (HTTP Basic), en-tête anti-CSRF `X-ImagUnity-Console` | `api`, `app/config` |
| Box — données reçues | messages IU-MSG (enveloppes chiffrées et signées), descriptions de fichiers, morceaux, paquets | signature de la box émettrice d'après l'annuaire ; chiffrement pour la box | `transport/iu`, `orchestration`, `packaging` |
| Box — annuaire et configuration | `GET` des entrées et des configurations | signature du responsable (clé épinglée, empreinte vérifiée hors bande) | `directory/LocalDirectory`, `app/config/CentralRules` |
| Box — mises à jour | manifeste et artefact | signature de l'éditeur | `release` |
| Relais — IU-FILE | `POST/PUT/GET/DELETE /iu/v1/chunks…` | dépôt : identité de la box enrôlée ; accès : capacité Ed25519 | `relay/ChunkController`, `RequestAuthenticator` |
| Relais — IU-MSG | `/iu/v1/queues…` | dépôt : identité ; relève : capacité de la file | `relay/QueueController` |
| Relais — sondes | `/actuator/health/**` | aucune | `relay` |
| Répartiteur (Traefik) | port 8443 (HTTP derrière un proxy TLS) | — | `deploy/relais-reparti`, `deploy/k3s` |
| Annuaire — lecture | `GET /directory/v1/**` | aucune (documents signés) | `directory-server` |
| Annuaire — dépôt | `POST /directory/v1/entries`, `/configurations` | compte du responsable (HTTP Basic) ; le service revérifie les signatures | `directory-server` |
| Annuaire — comptes rendus des box | `POST /directory/v1/configurations/reports` | signature de la box | `directory-server` |
| Annuaire — interface d'administration | `/admin/**`, `/admin/api/**` | compte du responsable ; en-tête `X-ImagUnity-Admin` ; CSP `default-src 'self'` | `directory-server` |

## 7. Mise en œuvre d'une pile d'essai

Pile Docker complète (deux sites avec Orthanc, deux box, relais, annuaire), décrite dans [deploy/README.md](../../deploy/README.md) ; variantes : deux relais indépendants (`compose.deux-relais.yml`), relais réparti avec Traefik et S3 (`compose.relais-reparti.yml`), TPM logiciel (`campagne/compose.tpm-logiciel.yml`), mises à jour signées (`compose.mises-a-jour.yml`). Manifestes K3s : [deploy/k3s/README.md](../../deploy/k3s/README.md).

- **Données exclusivement synthétiques** (générateur de la campagne : patient « SYNTHETIQUE^CAMPAGNE », pixels aléatoires). Aucune donnée réelle de patient n'est fournie ni ne doit être utilisée.
- Comptes de test de la pile : opérateur `operateur` / `essai-synthetique`, responsable `responsable` / `essai-synthetique` (à changer pour tout autre usage).
- Clés de test : à générer par l'auditeur avec l'outil du responsable (`DirectoryAdmin organisation-creer`, `enroler`…) pour disposer de ses propres clés d'organisation et de box.
- Campagne de scénarios rejouable (`deploy/campagne/campagne.py`) : coupures, pare-feu, liaisons dégradées, relais arrêtés, volume, inspection en clair du stockage du relais.
- Construction : `cd backend && ./mvnw verify` (tests et couverture), `cd frontend && npm ci && npm run build`.

## 8. Mesures déjà en place (à vérifier par l'auditeur)

- Tests automatisés sur chaque module, couverture minimale imposée (95 % des lignes, 75 % des branches), vecteurs de test pour les documents signés.
- Analyse des dépendances à chaque intégration (OSV-Scanner sur la nomenclature CycloneDX, exceptions datées).
- Campagne d'essais réels : aucune donnée en clair sur le relais ni sur le stockage S3 (inspection octet par octet, entropie des morceaux 8,0 bits/octet).
- Journaux : jamais de valeur lue dans les instances DICOM (noms de règles, identifiants techniques seulement).
- Pré-audit interne (§ 10).

## 9. Limites connues et choix assumés

À examiner, sans les découvrir : elles sont documentées.

- **Métadonnées visibles du relais** : quelle box dépose dans quelle file (dépôt authentifié par l'identité), volumes et horaires, taille approximative des paquets (nombre de morceaux). Pistes futures : jetons de dépôt anonymes ([spécification § 12](../specification-protocole.md)).
- **Annonce anticipée** : le relais voit un retrait commencer avant la fin du dépôt (association par les horaires).
- **Relais réparti** : PostgreSQL et le stockage S3 deviennent les points critiques du site relais ; leur redondance relève de l'exploitation.
- **Interface d'administration** : HTTP Basic du compte du responsable ; pas encore de comptes distincts ni de rôles.
- **Clés de la box** : en fichier chiffré (ML-DSA et ML-KEM pas encore pris en charge par les TPM) ; le TPM scelle le secret qui les protège.
- **Box antérieures** : refusent les champs inconnus des documents signés ; d'où un ordre de mise à jour imposé (annuaire, relais, box) avant d'utiliser les groupes de box.

## 10. Pré-audit interne

Revue systématique faite avant l'audit, par zone : cryptographie et documents signés ; relais ; données reçues par la box ; console, API et entrée DICOM ; service d'annuaire, mises à jour et déploiement. Constats et suites données : [pre-audit.md](pre-audit.md).

## 11. Attendus

- Rapport : constats classés par gravité (avec score CVSS v3.1 ou v4), preuve de concept quand c'est possible, recommandation ; synthèse pour la direction.
- Avis motivé sur la conception cryptographique (§ 5) et sur les choix assumés (§ 9).
- Contre-audit des corrections (vérification des correctifs des constats critiques et élevés).
- Échanges : un interlocuteur technique désigné côté projet ; questions et réponses tracées.
