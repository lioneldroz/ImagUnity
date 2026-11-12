# ImagUnity — Plan de réalisation

> Document de travail, établi à partir de la [note d'architecture](ImagUnity%20—%20Note%20d'architecture.docx).
> Stack retenue : **Java 25** pour le backend, **React** pour le frontend.

## Objectif de la preuve de concept

Démontrer une seule chose : un examen part d'un PACS, traverse des relais aveugles sans aucun port entrant, et arrive intact dans un autre PACS, y compris sur une liaison dégradée. Tout le reste attend le pilote.

## 1. Choix techniques

| Domaine | Choix | Raison |
|---|---|---|
| Backend | Java 25 + Spring Boot 4 | Supporte Java 25. Les threads virtuels conviennent aux transferts longs exécutés en parallèle. |
| Build | Maven multi-modules (avec Maven Wrapper) | Simple et lisible pour un projet découpé en modules. |
| DICOM | dcm4che | Bibliothèque Java de référence (C-STORE en réception et en envoi). Licence MPL/LGPL compatible avec une box propriétaire. |
| Compression sans perte | zstd (zstd-jni) | Rapide, bon taux de compression, réglable selon la qualité du réseau. |
| Transport | `simplex-chat` en ligne de commande, **processus séparé**, piloté par son API WebSocket (`java.net.http.WebSocket`) | Première voie de la note pour la licence AGPL : aucun lien en bibliothèque. |
| État des transferts (box) | H2 embarquée + Flyway | La box doit reprendre un transfert après une coupure de courant, sans serveur de base à administrer sur place. |
| Frontend | React + TypeScript + Vite, TanStack Query | Outillage standard et léger. |
| Environnement de test | Docker Compose : `smp-server`, `xftp-server`, 2 × `simplex-chat`, 2 × box, 2 × Orthanc ; Toxiproxy ou `tc netem` pour dégrader la liaison | Couvre l'environnement décrit dans la note. |

### Bases de données : une par type de composant

| Composant | Stockage | Raison |
|---|---|---|
| Box (chez les établissements) | **H2** embarquée | Appliance installée sans compétence informatique : aucune base à installer, surveiller ou mettre à jour. Un seul processus écrit l'état des transferts d'une seule box. La résistance aux coupures de courant sera vérifiée en phase 8 (SQLite reste une alternative). |
| Relais SMP et XFTP | **Aucune base ImagUnity** | Serveurs SimpleX utilisés sans modification, avec leur propre stockage (journal SMP, fichiers XFTP). Le logiciel ImagUnity n'y écrit jamais : un relais aveugle ne détient aucune donnée applicative, et le modifier créerait des obligations AGPL. Si le relais propre IU-T est retenu ([spécification](specification-protocole.md)), il utilisera **PostgreSQL** pour ses métadonnées (morceaux, files, expirations). |
| Annuaire de l'organisation (pilote) | **PostgreSQL** | Service central partagé par toutes les box : box enrôlées, clés publiques, entrées signées, révocations. Accès concurrents, administration et sauvegardes. Absent de la preuve de concept (appairage par lien d'invitation SimpleX). |

### Architecture hexagonale : le transport isolé derrière une interface

Le cœur métier ne dépend pas de SimpleX.

```
domain/        modèle Examen, Transfert, Manifeste, machine à états
dicom/         adaptateur dcm4che (réception C-STORE, envoi C-STORE)
packaging/     archivage, compression, empreintes, découpage par série
transport/     TransportPort + implémentation SimplexChatTransport
persistence/   dépôts, migrations Flyway
api/           REST + flux temps réel pour la console
app/           assemblage Spring Boot, configuration
```

La question « réutiliser ou réimplémenter SimpleX ? » reste ouverte. Avec cette séparation, une réimplémentation propriétaire de SMP et XFTP ne remplacerait que l'adaptateur `transport/`. Le savoir-faire de la box (empaquetage, ordre d'envoi, parallélisme) reste dans le code propriétaire, comme le recommande la note.

## 2. Communication frontend ↔ backend : REST + SSE

**Recommandation : REST pour tout, plus SSE (Server-Sent Events) pour le suivi en temps réel.**

- La console fait surtout de la configuration (AET, contacts appairés, règles de routage) et de la consultation (transferts, historique) : c'est typiquement du REST.
- Le seul besoin « temps réel » va du serveur vers le navigateur : progression et changements d'état des transferts. SSE le couvre nativement dans Spring (`SseEmitter`), passe les proxys, se reconnecte automatiquement.
- Le WebSocket ne se justifie que pour un échange bidirectionnel intense, ce qui n'est pas le cas. On pourra en ajouter un plus tard sans rien casser.

À ne pas confondre : le backend parle **déjà** en WebSocket à `simplex-chat`, mais c'est un lien interne à la box.

La note exclut l'interface d'administration de la preuve de concept : la console React est construite en dernier, d'abord en lecture seule.

## 3. Phases de la preuve de concept

| Phase | Contenu | Durée indicative |
|---|---|---|
| 0. Socle | Arborescence, Maven multi-modules, frontend Vite, CI GitHub Actions, Docker Compose, jeux de données publics anonymisés | 1 semaine |
| 1. Exploration SimpleX | Piloter `simplex-chat` depuis Java : adresse, appairage par invitation, messages, envoi et réception de fichiers XFTP, événements. Mesurer la limite de taille (1 Go en v5.0.0) et la reprise après coupure. **Livrable :** conclusions + `TransportPort` stabilisé | 1 à 2 semaines |
| 2. Entrée DICOM | SCP C-STORE dcm4che, liste des AET appelants autorisés, correspondance AET appelé → contact (fichier de configuration), regroupement par étude (délai d'inactivité) | 1 semaine |
| 3. Empaquetage | Manifeste JSON (UID étude / série / instance, SHA-256 par instance), archive + zstd, découpage par série au-delà de la limite XFTP, tests d'aller-retour | 1 semaine |
| 4. Orchestrateur | Machine à états persistée `REÇU → EMPAQUETÉ → DÉPOSÉ → ANNONCÉ → ACQUITTÉ` (+ `EN_ERREUR`), reprise au démarrage ; même binaire émetteur ou récepteur | 1 à 2 semaines |
| 5. Réception et restitution | Téléchargement, vérification des empreintes, dépaquetage, validation et normalisation DICOM, C-STORE vers le PACS destinataire | 1 semaine |
| 6. Accusé de bout en bout | Message de retour attestant que l'examen est arrivé complet et identique | < 1 semaine |
| 7. API et console React | REST (transferts, contacts, AET, état), flux SSE, tableau des transferts, détail, indicateurs techniques | 1 à 2 semaines |
| 8. Campagne de tests | Scénarios de la note, automatisés (Testcontainers ou scripts Compose). **Livrable :** rapport de preuve de concept avec mesures ([rapport](phase8-campagne.md)) | 1 à 2 semaines |

**Durée totale indicative : 10 à 14 semaines pour une personne.**

### Scénarios de la phase 8

- Envoi d'un examen du PACS A vers le PACS B.
- Comparaison des UID et des empreintes à l'arrivée.
- Box derrière un pare-feu bloquant tout trafic entrant, avec scan de ports.
- Inspection du stockage des relais.
- Liaison bridée en débit, avec latence et pertes de paquets.
- Coupure réseau au milieu d'un transfert.
- Box réceptrice éteinte pendant l'envoi, rallumée plus tard.
- Examen de plusieurs Go.

## 4. Après la preuve de concept (pilote)

Par ordre de criticité :

1. **Annuaire signé** (service central de l'organisation, sur PostgreSQL) : appairage avec vérification de l'empreinte hors bande, révocation. *Fait : voir [annuaire-signe.md](annuaire-signe.md).*
2. **Chiffrement applicatif** avec la clé publique du destinataire ; étudier les algorithmes résistants au calcul quantique. *Fait (X25519 + ML-KEM-768, AES-256-GCM) : voir [chiffrement-applicatif.md](chiffrement-applicatif.md).*
3. **Mises à jour signées** : clé hors ligne, double signature. *Fait (lanceur qui vérifie et refuse les retours en arrière) : voir [mises-a-jour-signees.md](mises-a-jour-signees.md).*
4. Clés d'identité dans un **TPM** ou un élément sécurisé. *Fait par scellement dans le TPM (les TPM ne prennent pas encore en charge ML-DSA ni ML-KEM) : voir [cles-tpm.md](cles-tpm.md).*
5. Routage par champ DICOM, réception STOW-RS (DICOMweb).
6. Adaptation au réseau : taille des blocs, priorité aux séries utiles au diagnostic, redondance entre relais.
7. Validation juridique de la licence AGPL avant commercialisation.
8. **Transport propre IU-T** (équivalent de XFTP et SMP, en Java), derrière le même `TransportPort` : voir la [spécification](specification-protocole.md). Estimation : 2,5 à 3,5 mois, plus un audit de sécurité externe. Il rendrait le point 7 sans objet pour le transport.

## 5. Décisions

| Sujet | Décision |
|---|---|
| Build | Maven |
| Bases de données | H2 dans la box ; PostgreSQL pour l'annuaire (pilote) ; aucune base ImagUnity sur les relais |
| Frontend ↔ backend | REST + SSE (confirmé en phase 7) |
| Code des box existantes | À évaluer : il pourrait remplacer une partie des phases 2 et 5 |
