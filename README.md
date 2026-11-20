# ImagUnity

ImagUnity est une box qui transporte des examens d'imagerie DICOM entre deux établissements partenaires, où qu'ils soient dans le monde, **sans ouvrir aucun port entrant**.

Une box s'installe de chaque côté. Entre les deux, des relais ne voient passer que des données chiffrées de bout en bout.

## Principe : la poste restante

L'émetteur dépose l'examen chiffré sur un relais, et le destinataire vient le chercher quand il est prêt.

- **Connexions sortantes uniquement** : chaque box ouvre elle-même ses connexions vers les relais. Il suffit que le pare-feu autorise une connexion TLS sortante.
- **Relais aveugles** : les relais stockent des morceaux chiffrés dont ils n'ont pas les clés, ne connaissent ni l'émetteur ni le destinataire, et effacent chaque morceau une fois retiré.
- **Asynchrone** : l'émetteur et le destinataire n'ont pas besoin d'être connectés en même temps. Une coupure réseau retarde le transfert sans l'interrompre.

## Architecture

| Élément | Rôle |
|---|---|
| Box émettrice / réceptrice | Intégration PACS, routage DICOM, compression, chiffrement, découpage |
| Relais IU-T | Aveugle : morceaux chiffrés du contenu (IU-FILE) et files de messages chiffrés de signalisation (IU-MSG) ; dépôts réservés aux box enrôlées |
| Annuaire | Propre à l'organisation, signé par son responsable : clés des box et adresses de leurs files de réception |

Le transport est propre à ImagUnity : **IU-T** ([spécification](docs/reference/specification-protocole.md)), inspiré de SMP et XFTP de l'écosystème [SimpleX](https://simplex.chat), utilisés jusqu'à la phase 8 puis remplacés.

### Déroulé d'un envoi

1. Le PACS envoie l'examen à la box émettrice, comme à n'importe quelle destination DICOM.
2. La box identifie le destinataire (destination DICOM ou champ DICOM), puis récupère dans l'annuaire ses clés et l'adresse de sa file de réception.
3. Elle compresse l'examen sans perte, le chiffre pour le destinataire, le découpe et dépose les morceaux sur le relais.
4. Elle envoie la description du fichier, signée et chiffrée, dans la file du destinataire.
5. La box réceptrice télécharge les morceaux, vérifie leurs empreintes, déchiffre et décompresse l'examen.
6. Elle transmet l'examen au mini-PACS et renvoie un accusé de bout en bout, signé, dans la file de l'émetteur.
7. Les relais suppriment les morceaux après leur retrait ou à l'expiration de leur durée de séjour.

## Organisation du dépôt

```
backend/     Java 25, Spring Boot 4, Maven multi-modules
  domain/          modèle métier, machine à états des transferts
  directory/       annuaire signé : identités, entrées, clés protégées (phrase de passe, TPM)
  rules/           moteur de règles DICOM : conditions, priorité, routage
  dicom/           adaptateur DICOM (dcm4che) : C-STORE, STOW-RS, restitution
  configuration/   règles en vigueur : configuration centralisée, priorité locale rechargée à chaud
  packaging/       manifeste, compression zstd, découpage par série
  protocol/        formats IU-T partagés par la box et le relais
  transport/       port de transport et adaptateur IU-T
  relay/           relais IU-T (Spring Boot, PostgreSQL)
  directory-server/ service d'annuaire de l'organisation (PostgreSQL)
  release/         lanceur et outil de l'éditeur (versions signées)
  persistence/     état des transferts et partenaires (H2 + Flyway)
  orchestration/   orchestrateur des transferts, appairage
  api/             API REST (+ SSE) pour la console
  app/             assemblage Spring Boot de la box, console comprise
  scenarios/       tests de scénarios de bout en bout (box, relais, annuaire réels)
frontend/    console React + TypeScript (Vite)
deploy/      installation (site central, box systemd), livraison, banc d'essai (banc-essai/)
docs/        guides, référence, historique, audit : voir l'index docs/README.md
```

## Installation

Sous Rocky Linux 10, en deux parties :

- **site central** : relais IU-T, annuaire de l'organisation et serveur de mises à jour, sous Docker : [installation du site central](docs/guides/installation-site-central-rocky10.md) ;
- **box** de chaque établissement, sous systemd, sans root, avec mises à jour automatiques signées : [installation d'une box](docs/guides/installation-box-rocky10.md).

## Développement

Prérequis : JDK 25, Node.js 24, Docker (pour l'environnement de test).

Backend (API sur http://localhost:8080) :

```bash
cd backend
./mvnw verify
java -jar app/target/imagunity-box.jar
```

`./mvnw verify` mesure la couverture des tests (JaCoCo) et **échoue sous 95 % des lignes ou 75 % des branches**, module par module. Rapport par module : `<module>/target/site/jacoco/index.html`. Seuils modifiables en ligne de commande : `-Dcouverture.lignes.min=0.90 -Dcouverture.branches.min=0.70`.

### Tests de scénarios

`./mvnw verify` rejoue aussi des **scénarios de bout en bout** dans une seule JVM, avec deux vraies box, un vrai relais IU-T, le vrai service d'annuaire et deux PACS simulés :

- transfert d'un examen de A vers B en C-STORE et en STOW-RS ;
- routage par champ DICOM et refus à l'entrée DICOM ;
- pannes de PACS, de box et de relais ;
- révocation et expiration dans l'annuaire.

Liste des scénarios et des autres tests : [backend/scenarios/README.md](backend/scenarios/README.md).

### Accessibilité (RGAA 4.1.2)

Le job **Accessibilité (RGAA 4.1.2)** de la CI contrôle l'interface d'administration de l'annuaire (`/admin/`), l'interface locale du responsable et la console de la box : validation HTML, axe-core limité aux règles du RGAA, et mesures que les outils ne font pas (contrastes des champs, messages de statut, affichage à 320 px, espacement du texte, focus, lien d'évitement), en thème clair et sombre. Il couvre une partie seulement des critères : il évite les régressions, il ne remplace pas un audit. Détails et lancement en local : [accessibilite/README.md](accessibilite/README.md).

### Vulnérabilités des dépendances

Le workflow **Vulnérabilités (CVE)** (`.github/workflows/vulnerabilites.yml`) passe les dépendances au crible de la base [OSV](https://osv.dev) avec OSV-Scanner :
- **backend** : nomenclature CycloneDX produite par Maven, avec les versions réellement résolues, transitives comprises (hors dépendances de test) ;
- **frontend** : `frontend/package-lock.json` ;
- **contrôles d'accessibilité** : `accessibilite/package-lock.json` ;
- **campagne de tests** : `deploy/banc-essai/campagne/requirements.txt`.

Il tourne à chaque push sur `main`, sur chaque pull request, **chaque lundi** (un avis publié après coup est vu sans attendre un push) et à la demande (onglet *Actions*). Il **échoue dès qu'une vulnérabilité connue est trouvée** ; le rapport s'affiche dans le résumé de l'exécution.

Exceptions : `osv-scanner.toml`, chacune avec sa justification et une **date d'expiration** ; passée cette date, la CI échoue de nouveau. En local :

```bash
(cd backend && ./mvnw -q org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom -DoutputFormat=json -DoutputName=sbom.cdx)
osv-scanner scan source --config osv-scanner.toml -L backend/target/sbom.cdx.json \
    -L frontend/package-lock.json -L accessibilite/package-lock.json -L deploy/banc-essai/campagne/requirements.txt
```

Frontend (http://localhost:5173, les appels `/api` sont relayés vers le backend) :

```bash
cd frontend
npm install
npm run dev
```

Banc d'essai complet sur une machine (relais, deux box, deux Orthanc), pour la campagne de tests : voir [deploy/banc-essai/README.md](deploy/banc-essai/README.md).

## Documentation

Index complet : [docs/README.md](docs/README.md).

- **Administrer le réseau** : [guide du responsable de l'organisation](docs/guides/guide-responsable.md) (annuaire, enrôlement des box, règles communes).
- **Installer et livrer** : [site central](docs/guides/installation-site-central-rocky10.md), [box](docs/guides/installation-box-rocky10.md), [livrer une version](docs/guides/livraison.md), [notes de version](docs/guides/notes-de-version.md).
- **Comprendre** : [spécification du protocole IU-T](docs/reference/specification-protocole.md), [fédération entre organisations](docs/reference/federation-organisations.md), [annuaire signé](docs/reference/annuaire-signe.md), [chiffrement applicatif](docs/reference/chiffrement-applicatif.md), [configuration centralisée](docs/reference/configuration-centralisee.md), [mises à jour signées](docs/reference/mises-a-jour-signees.md), [licences des composants](docs/reference/licences.md).
- **Auditer** : [dossier d'audit](docs/audit/dossier-audit.md), [cahier des charges](docs/audit/cahier-des-charges.md), [pré-audit interne](docs/audit/pre-audit.md).
- **Présenter** : [le voyage d'un examen](docs/presentation/README.md), [films explicatifs](docs/films/README.md) (six vidéos courtes).
- **Historique** : [note d'architecture](docs/historique/note-architecture.md), [plan de réalisation](docs/historique/plan-de-realisation.md), phases 1 à 8, [plan d'amélioration de la structure](docs/historique/plan-amelioration-structure.md).

## Statut

- **Preuve de concept terminée** (phases 1 à 8) : la chaîne PACS A → box A → relais → box B → PACS B fonctionne sur le transport propre IU-T, avec accusé de bout en bout et suivi en direct dans la console. La campagne de tests (pare-feu, liaison bridée, coupure, destinataire éteint, 2 Go) est passée en réel, toutes instances identiques à l'arrivée ([rapport](docs/historique/phase8-campagne.md)).
- **Fonctions du pilote faites et vérifiées sur le banc d'essai** : annuaire signé, chiffrement applicatif des paquets (X25519 + ML-KEM-768), mises à jour signées, clés protégées par le TPM, routage par champ DICOM, réception STOW-RS, priorité des séries, plusieurs relais et relais réparti, configuration centralisée, enrôlement automatique par code, interface locale du responsable, fédération entre organisations (étape 1, [banc à deux organisations](deploy/banc-essai/federation/README.md)).
- **Préparation du pilote** : pré-audit interne fait et ses constats corrigés ; installations sous Rocky Linux 10 (site central sous Docker, box sous systemd sans root) vérifiées en CI ; chaîne de livraison en place (version 0.15.0). Reste l'audit externe ([cahier des charges](docs/audit/cahier-des-charges.md)) et le déploiement sur site.

## Licence

ImagUnity est un logiciel propriétaire : tous droits réservés ([LICENSE](LICENSE)). Les composants tiers livrés, et leurs licences, sont listés dans [NOTICE.md](NOTICE.md) ; leur contrôle est décrit dans [licences des composants](docs/reference/licences.md).
