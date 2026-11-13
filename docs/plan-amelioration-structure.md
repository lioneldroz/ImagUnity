# Analyse de la structure et plan d'amélioration

> Relevé du 13 novembre 2026, sur la branche principale. Le projet est sain dans l'ensemble : modules bien découpés, dépendances sans cycle, tests nombreux, couverture imposée par la CI. Les améliorations ci-dessous visent l'industrialisation (livrer, versionner, vérifier) et le rangement, pas une refonte.

## 1. État des lieux

### Backend : 13 modules Maven

| Module | Code (lignes) | Tests (lignes) | Dépend de |
|---|---|---|---|
| `domain` | 649 | 408 | — |
| `directory` | 2 555 | 1 377 | — |
| `protocol` | 902 | 586 | directory |
| `release` | 889 | 456 | directory |
| `relay` | 1 885 | 1 022 | protocol |
| `directory-server` | 884 | 402 | directory, **dicom** |
| `dicom` | 2 056 | 1 957 | domain |
| `packaging` | 672 | 713 | domain |
| `transport` | 3 647 | 2 447 | domain, protocol (relay pour les tests seulement) |
| `persistence` | 419 | 418 | domain, directory |
| `orchestration` | 1 915 | 2 734 | domain, packaging, transport, directory |
| `api` | 984 | 1 124 | domain, orchestration, directory, dicom |
| `app` | 2 230 | 2 062 | api, persistence, directory, transport, packaging, dicom |

Points forts :
- Le graphe des dépendances est propre, sans cycle.
- Le relais ne dépend que de `protocol` : il ne connaît ni DICOM ni la box.
- Le transport n'utilise le relais que dans ses tests.
- Les tests représentent à peu près autant de lignes que le code, et la CI impose une couverture de 95 % des lignes par module.

### Autres parties

| Partie | Contenu |
|---|---|
| `frontend/` | Console React (8 composants, 7 modules d'API), lint et build en CI |
| `deploy/` | Deux installations : `site-central/` et `box-systemd/`. Plusieurs pièces du banc d'essai : `docker-compose.yml` et ses surcouches, `box/`, `campagne/`, `tpm/`, `relais-reparti/`, `k3s/` |
| `docs/` | 24 documents à plat (guides, références, historique des phases) et une note d'architecture au format Word ; sous-dossiers `audit/` et `presentation/` |
| `.github/workflows/` | CI (backend, frontend, validation du Compose du banc) et recherche de vulnérabilités (OSV) |

## 2. Constats

| N° | Constat | Conséquence | Gravité |
|---|---|---|---|
| S1 | **La console React n'est livrée nulle part.** Le jar de la box ne sert que l'API ; la console ne tourne qu'en développement (`npm run dev`). | Les guides d'installation enrôlent et appairent la box en `curl`, et un site n'a pas d'interface de suivi | Élevée |
| S2 | **Deux numéros de version sans lien.** Maven est en `0.1.0-SNAPSHOT`, la console en `0.1.0`. Les versions signées (0.14.x, n° 26…) sont choisies à la main au moment de la signature, et le registre des numéros est tenu de mémoire. | Risque de numéro réutilisé ou sauté ; une box lancée hors lanceur annonce `0.1.0-SNAPSHOT` | Élevée |
| S3 | **Pas de chaîne de livraison.** Le jar de la box, sa signature, l'archive `imagunity-box-<v>.tar.gz` et les images du site central sont construits à la main. | Livraison non reproductible, étapes oubliables | Moyenne |
| S4 | **La CI ne vérifie pas les installations.** Elle ne valide que le Compose du banc d'essai. Le Compose du site central, le `Caddyfile`, les unités systemd, la règle polkit et les scripts shell ne sont pas contrôlés. | Une erreur de syntaxe n'apparaît qu'à l'installation : c'est ainsi que trois scripts non versionnés n'ont été vus qu'à l'essai | Moyenne |
| S5 | **Logique métier dans l'assemblage.** Le module `app` (20 classes dans `config`) contient, à côté du câblage Spring, de la logique propre : `CentralRules` (application de la configuration centrale, 300 lignes), `TpmSeal` (protection des clés), `ReloadablePriority`, `ExposureCheck`. | Logique moins réutilisable et testée à travers Spring ; `app` devient un fourre-tout | Moyenne |
| S6 | **L'annuaire embarque l'entrée DICOM.** `directory-server` dépend de tout le module `dicom` (dcm4che, SCP, STOW) pour un seul usage : essayer les règles (`RuleSet`) dans l'interface d'administration. | Dépendances et surface d'attaque inutiles côté serveur | Faible à moyenne |
| S7 | **Code de migration devenu obsolète**, à retirer ou à borner : | Code mort ; le dernier point est un risque de sécurité si le mode reste activable en production | Moyenne |
| | – reprise des partenaires SimpleX (`TransportConfiguration.migratePeers`, identifiants IU-T à partir d'un million) ; | | |
| | – mode « paquets non chiffrés » pour la migration d'un parc (`IMAGUNITY_DIRECTORY_ENCRYPTION_REQUIRED=false`). | | |
| S8 | **Documentation à plat et en partie périmée.** Guides, références, historique des phases et audit sont mêlés dans `docs/`. Le README liste encore la configuration centralisée comme « plan, plus tard », la spécification en « v0.6, étape 6 en cours », et un statut « pilote en cours » antérieur au pré-audit. La note d'architecture est un `.docx`, ni lisible dans le dépôt ni comparable d'une version à l'autre. | Difficile de savoir quel document fait foi | Moyenne |
| S9 | **`deploy/` mêle installation et banc d'essai.** Une dizaine de fichiers et dossiers du banc sont à côté des deux installations. | Confusion entre ce qui s'installe et ce qui sert aux essais | Faible |

## 3. Plan

Ordre proposé : d'abord ce qui bloque le pilote (lots 1 à 3), puis le rangement (lots 4 à 6). Chaque lot est une branche, avec ses tests, puis une fusion.

| Lot | Contenu | Constats | Charge |
|---|---|---|---|
| **1. Console livrée avec la box** (fait) | Construire la console au build Maven et l'embarquer dans le jar de la box, servie sur `/` (avec la CSP et l'en-tête anti-CSRF existants) ; essai réel ; guides de la box mis à jour (enrôlement et appairage depuis la console, `curl` en annexe) | S1 | 2 à 3 j |
| **2. Version unique et livraison** (fait, [livraison](livraison.md)) | Une seule version, celle de Maven, reprise par la console, le manifeste signé et l'archive ; registre des numéros de version tenu dans le dépôt (`releases.json`, numéro suivant calculé et vérifié). Un flux de livraison, lancé à la main, produit : le jar, l'archive non signée, les images du site central. La **signature reste hors ligne**, chez l'éditeur ; un script la vérifie avant publication. | S2, S3 | 2 à 3 j |
| **3. CI des installations** (fait) | Validation du Compose du site central et du `Caddyfile` (`caddy validate`) ; `systemd-analyze verify` sur les unités ; `shellcheck` sur les scripts ; construction de l'archive de la box ; essai d'`installer.sh` dans un conteneur Rocky Linux 10 | S4 | 1 à 2 j |
| **4. Retrait du code de migration** | Supprimer la reprise SimpleX et le mode « paquets non chiffrés » ; garder la migration phrase de passe → TPM, toujours utile. Notes de version : parc SimpleX, ou box en mode non chiffré, à mettre à jour d'abord avec la version actuelle | S7 | 1 j |
| **5. Modules : logique hors de `app`** | `CentralRules` et `ReloadablePriority` dans un module `configuration` ; `TpmSeal` dans `directory`, à côté de la protection des clés ; moteur de règles (`DicomCondition`, `RuleSet`) dans un petit module `rules` (dcm4che-core seul), utilisé par `dicom` et `directory-server`, qui ne dépend plus de `dicom` | S5, S6 | 2 à 3 j |
| **6. Documentation et `deploy/`** | Rangement : | S8, S9 | 1 à 2 j |
| | – `docs/` en `guides/`, `reference/`, `historique/` (phases 1 à 8, plan de réalisation), `audit/`, avec un index ; | | |
| | – README mis à jour (statut, liste des documents) ; | | |
| | – note d'architecture reprise en Markdown, ou classée comme document historique ; | | |
| | – banc d'essai regroupé sous `deploy/banc-essai/` (chemins de la campagne et de la CI ajustés). | | |

**Ce que ce plan ne change pas** : le découpage métier des modules, le protocole IU-T, les deux installations récentes, et les décisions du pré-audit.

**Points de vigilance**

- **Lot 2, numéros de version.** Les numéros sont croissants et le retour en arrière est refusé par les box. Le registre doit donc partir du plus haut numéro déjà signé : 29 dans les essais.
- **Lot 4, ordre de mise à jour.** Toute box encore en mode non chiffré, ou issue de SimpleX, doit passer par la version actuelle avant la suppression.
- **Lot 6, liens.** Le déplacement des documents casse les liens : il faut chercher et corriger tous les renvois, y compris ceux de la présentation et du dossier d'audit.
