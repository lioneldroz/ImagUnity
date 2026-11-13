# Documentation d'ImagUnity

## Guides : installer, livrer, mettre à jour

| Document | Pour qui |
|---|---|
| [Installation du site central sur Rocky Linux 10](guides/installation-site-central-rocky10.md) | exploitant de l'organisation : relais IU-T, annuaire, serveur de mises à jour (Docker) |
| [Installation d'une box sur Rocky Linux 10](guides/installation-box-rocky10.md) | exploitant d'un établissement : box sous systemd, sans root, mises à jour automatiques |
| [Livrer une version](guides/livraison.md) | éditeur : version, registre des numéros, construction, signature hors ligne, publication |
| [Notes de version](guides/notes-de-version.md) | tous : ce qu'il faut faire avant une mise à jour |

## Référence : comment ça marche

| Document | Sujet |
|---|---|
| [Spécification du protocole IU-T](reference/specification-protocole.md) | transport : relais IU-FILE et IU-MSG, files, morceaux, plusieurs relais, relais réparti |
| [Annuaire signé](reference/annuaire-signe.md) | identité des box, enrôlement, révocation, messages signés, protection des clés |
| [Chiffrement applicatif des paquets](reference/chiffrement-applicatif.md) | chiffrement pour la box destinataire (X25519 + ML-KEM-768) |
| [Clés de la box protégées par le TPM](reference/cles-tpm.md) | secret scellé dans le TPM, passage depuis la phrase de passe |
| [Mises à jour signées](reference/mises-a-jour-signees.md) | lanceur, signature de l'éditeur, anti-retour en arrière |
| [Configuration centralisée](reference/configuration-centralisee.md) | règles signées par le responsable, appliquées sans redémarrage |
| [Priorité des séries](reference/priorite-dicom.md) | séries utiles au diagnostic envoyées et restituées d'abord |
| [Routage par champ DICOM](reference/routage-dicom.md) | choix du destinataire d'après le contenu de l'examen |
| [Réception DICOMweb STOW-RS](reference/stow-rs.md) | dépôt des examens par HTTP |

## Audit

| Document | Sujet |
|---|---|
| [Dossier d'audit](audit/dossier-audit.md) | périmètre, mécanismes de sécurité, surfaces d'attaque |
| [Cahier des charges de l'audit externe](audit/cahier-des-charges.md) | ce qui est demandé à l'auditeur |
| [Pré-audit interne](audit/pre-audit.md) | revue interne et constats corrigés |

## Présentation

[Le voyage d'un examen](presentation/README.md) : présentation du projet.

## Historique

Documents de travail, gardés pour la trace des décisions ; ils décrivent l'état du projet à leur date.

| Document | Sujet |
|---|---|
| [Note d'architecture](historique/note-architecture.md) | architecture de départ (9 novembre 2026), sur SimpleX ; original Word à côté |
| [Plan de réalisation](historique/plan-de-realisation.md) | phases de la preuve de concept, priorités du pilote |
| [Phase 1 — Exploration SimpleX](historique/phase1-simplex.md) | |
| [Phase 2 — Entrée DICOM](historique/phase2-dicom.md) | |
| [Phase 3 — Empaquetage](historique/phase3-empaquetage.md) | |
| [Phase 4 — Orchestrateur](historique/phase4-orchestrateur.md) | |
| [Phase 5 — Réception et restitution](historique/phase5-reception.md) | |
| [Phase 6 — Accusé de bout en bout](historique/phase6-accuse.md) | |
| [Phase 7 — API et console React](historique/phase7-console.md) | |
| [Phase 8 — Campagne de tests](historique/phase8-campagne.md) | rapport de la campagne, rejouée sur IU-T |
| [Analyse de la structure et plan d'amélioration](historique/plan-amelioration-structure.md) | lots 1 à 6 de rangement du dépôt |

Les captures d'écran sont dans [`images/`](images/).
