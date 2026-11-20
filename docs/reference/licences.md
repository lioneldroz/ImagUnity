# Licences : ImagUnity et ses composants tiers

ImagUnity est un **logiciel propriétaire** : tous droits réservés (fichier [`LICENSE`](../../LICENSE)). Il peut être
vendu et livré sans publier son code source, à condition que chacun des composants tiers qu'il intègre le permette.
Cette page dit comment c'est vérifié, ce qui est autorisé, et ce qu'il faut faire quand un composant change.

## 1. La règle

Un composant tiers **livré** (intégré à la box, au relais, au service d'annuaire, à l'outil du responsable ou à la
console de la box) doit avoir une licence qui autorise un usage commercial fermé, sans contaminer le code
d'ImagUnity :

| Catégorie | Licences | Livrable | Obligations |
|---|---|---|---|
| **Permissives** | Apache-2.0, MIT, MIT-0, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, CC0-1.0, Unlicense, Zlib, PostgreSQL… | Oui | Mentionner le composant et sa licence (NOTICE.md) |
| **Copyleft faible** | MPL-1.1, MPL-2.0, EPL-1.0, EPL-2.0, CDDL, GPL avec exception de liaison (Classpath) | Oui | Les mêmes, et publier toute modification **des fichiers du composant** sous sa licence ; le code d'ImagUnity n'est pas concerné |
| **Copyleft fort** | GPL, AGPL, LGPL sans autre choix, SSPL, licences « non commerciales » | **Non** | Le composant doit être remplacé |
| **Inconnue ou absente** | — | **Non**, tant qu'elle n'est pas vérifiée | Vérifier la licence à la source, puis la déclarer (§ 4) |

Quand un composant propose **plusieurs licences au choix** (par exemple « MPL 1.1 ou GPL 2 ou LGPL 2.1 »), ImagUnity
retient la plus favorable : une licence permissive si elle est proposée, sinon une licence à copyleft faible.

La licence LGPL est refusée quand elle est la seule possible : son obligation de permettre le remplacement de la
bibliothèque dans le produit livré se prête mal à des jar autonomes et à des images signées.

## 2. Le contrôle automatique

Le job **« Licences des composants »** de la CI (`.github/workflows/ci.yml`) lance
[`deploy/licences/verifier-licences.py`](../../deploy/licences/verifier-licences.py) à chaque push et sur chaque
pull request. Il lit :

| Source | Composants | Livrés ? |
|---|---|---|
| Nomenclature CycloneDX du backend (`cyclonedx-maven-plugin`) | Dépendances Java des jar, transitives comprises, hors tests | Oui |
| `frontend/package-lock.json` | Dépendances de production de la console de la box | Oui |
| `frontend/package-lock.json` (dépendances de développement) | Outils de construction (Vite, lightningcss…) | Non |
| `accessibilite/package-lock.json` | Outils des tests d'accessibilité (Playwright, axe-core) | Non |
| `deploy/banc-essai/campagne/requirements.txt` | Outils de la campagne de tests (pydicom) | Non |

Les outils non livrés suivent la même règle : un outil sous licence contaminante ne contamine pas ImagUnity tant
qu'il n'est pas livré, mais la règle unique évite qu'il le devienne par mégarde.

Le job échoue si une licence est refusée, inconnue ou absente, ou si `NOTICE.md` n'est plus à jour.

Pour le lancer sur un poste :

```bash
cd backend && sh ./mvnw -B -ntp org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom \
    -DoutputFormat=json -DoutputName=sbom.cdx && cd ..
python3 deploy/licences/verifier-licences.py --sbom backend/target/sbom.cdx.json \
    --npm frontend/package-lock.json --npm-outil accessibilite/package-lock.json \
    --pip-outil deploy/banc-essai/campagne/requirements.txt --notice NOTICE.md
```

La politique (licences autorisées, noms à normaliser, licences vérifiées à la main) est dans
[`deploy/licences/politique.json`](../../deploy/licences/politique.json).

## 3. État au 20/11/2026

Aucun composant livré ni aucun outil n'est sous licence contaminante. Composants à copyleft faible livrés :

| Composant | Licences proposées | Licence retenue | Usage |
|---|---|---|---|
| dcm4che (core, dict, net) | MPL 1.1 ou GPL 2 ou LGPL 2.1 | MPL-1.1 | Réseau et dictionnaire DICOM de la box |
| logback (classic, core) | EPL 2.0 ou LGPL 2.1 | EPL-2.0 | Journaux |
| jakarta.annotation-api | EPL 2.0 ou GPL 2 avec exception Classpath | EPL-2.0 | Annotations Java |
| H2 | MPL 2.0 ou EPL 1.0 | MPL-2.0 | Base de données embarquée |

ImagUnity les utilise **sans modification** : il suffit de les mentionner (NOTICE.md) et d'indiquer où trouver leur
code source (leurs dépôts publics). La liste complète des composants livrés est dans [`NOTICE.md`](../../NOTICE.md),
livré à la racine de chaque version (`.github/workflows/livraison.yml`).

## 4. Quand une dépendance change

- **Ajout ou mise à jour d'une dépendance** : la CI relance le contrôle. Si `NOTICE.md` n'est plus à jour, le
  régénérer avec la commande du § 2 et le committer.
- **Licence refusée** : remplacer le composant, ou en choisir une autre version. Ne pas élargir la politique sans avis
  juridique.
- **Licence inconnue** (nom non reconnu) : si c'est une licence autorisée écrite autrement, ajouter sa correspondance
  dans `noms` (par exemple `"The MIT License": "MIT"`).
- **Licence absente** : vérifier la licence à la source (fichier LICENSE du dépôt du composant), puis la déclarer
  dans `declarees`, avec l'adresse de la source.

## 5. Ce que le contrôle ne voit pas

Le contrôle porte sur les dépendances déclarées par Maven et npm. Les composants suivants sont livrés ou utilisés
autour d'ImagUnity, sans passer par elles ; leurs licences ont été vérifiées à la main :

| Composant | Où | Licence | Usage commercial |
|---|---|---|---|
| Eclipse Temurin (OpenJDK 25) | Images du relais et de l'annuaire, box | GPL-2.0 avec exception Classpath | Oui : l'exception couvre les applications qui l'utilisent |
| PostgreSQL 17 | Site central (Compose) | PostgreSQL (permissive) | Oui |
| Caddy 2 | Site central (Compose) | Apache-2.0 | Oui |
| Traefik 3 | Banc d'essai à deux relais | MIT | Oui |
| SeaweedFS | Stockage S3 du banc d'essai | Apache-2.0 | Oui |
| Rocky Linux 10, Ubuntu | Système des box et des images | Diverses (GPL pour le noyau et les outils) | Oui : ImagUnity s'exécute dessus sans les modifier ni s'y lier |
| swtpm | Banc d'essai (TPM logiciel) | BSD-3-Clause | Oui |
| **Orthanc** | **Banc d'essai uniquement** (PACS de test) | **GPL-3.0** | **Ne jamais l'intégrer à un produit livré** |

## 6. Limites

Le contrôle se fie aux licences **déclarées** par les composants. Il ne remplace pas une revue juridique, à faire
avant la première commercialisation, notamment sur les composants à copyleft faible et sur les licences des images
de base. Le titulaire des droits d'ImagUnity est à compléter dans `LICENSE`.
