# Livrer une version d'ImagUnity

> D'une version de développement à des box mises à jour : une seule version pour tout le projet, des numéros tenus par un registre, une construction automatique, une signature **hors ligne**, une publication vérifiée.

```
 dépôt                    GitHub Actions             poste de l'éditeur (hors ligne)      site central
 fixer-version 0.15.0 ──► étiquette v0.15.0 ──► construction, tests,  ──► signer --registre ──► publier-version.sh
 (pom.xml, console)       (workflow Livraison)   jar non signé, images     (n° suivant du        (vérifie signature,
                                                  du site central            registre)             empreinte, registre)
                                                                            construire-archive      canal pilote, puis général
```

## 1. Une seule version

La version vit dans les `pom.xml` du backend et dans `frontend/package.json` : `X.Y.Z-SNAPSHOT` pendant le développement, `X.Y.Z` pour une livraison. On ne la modifie qu'avec ce script :

```bash
sh deploy/livraison/fixer-version.sh 0.15.0            # avant la livraison
sh deploy/livraison/fixer-version.sh 0.16.0-SNAPSHOT   # juste après
```

Le jar de la box porte cette version (`Implementation-Version` de son manifeste). La console l'affiche, l'outil de l'éditeur la reprend en la signant, et l'archive de la box en tire son nom.

## 2. Le registre des numéros

Chaque version signée de la box reçoit un **numéro** strictement croissant. Les box refusent toute version de numéro inférieur à celle qu'elles exécutent.

Le registre [`deploy/livraison/registre-versions.json`](../deploy/livraison/registre-versions.json), versionné dans le dépôt, tient ces numéros. Il garantit :
- qu'un numéro n'est **ni réutilisé ni sauté** : l'outil prend le suivant lui-même ;
- qu'une version n'est **signée qu'une fois** ;
- qu'elle est **plus récente** que la dernière livrée, et **jamais `-SNAPSHOT`** ;
- qu'un même jar n'est pas signé sous deux versions.

Les numéros 1 à 29 ont servi aux essais, avec une clé d'essai : le premier numéro livré est 30.

## 3. Construire (GitHub Actions)

```bash
sh deploy/livraison/fixer-version.sh 0.15.0
git commit -am "Version 0.15.0" && git tag v0.15.0 && git push origin main v0.15.0
```

L'étiquette déclenche le workflow **Livraison** (`.github/workflows/livraison.yml`). Il vérifie que l'étiquette, les `pom.xml` et la console portent la même version, sans `-SNAPSHOT`. Il lance ensuite la construction complète avec tous les tests, puis dépose l'artefact `imagunity-0.15.0` (gardé 30 jours) :

| Fichier | Contenu |
|---|---|
| `box/imagunity-box.jar` | La box, console comprise, **non signée** |
| `box/imagunity-release.jar` | Le lanceur, et l'outil de l'éditeur |
| `site-central/images.tar.gz` | Images `imagunity/relay:0.15.0` et `imagunity/directory:0.15.0` |
| `SHA256SUMS` | Empreintes de tous les fichiers |

Lancé à la main (onglet Actions, « Run workflow »), le même workflow fait un **essai à blanc** sur la version en cours, `-SNAPSHOT` compris.

Remettre ensuite le dépôt en développement : `fixer-version.sh 0.16.0-SNAPSHOT`, puis un commit.

## 4. Signer (poste de l'éditeur, hors ligne)

Copier sur le poste hors ligne :
- l'artefact du workflow ;
- le registre à jour (`git pull`).

Puis :

```bash
sha256sum -c SHA256SUMS                                   # dans le dossier de l'artefact
java -jar box/imagunity-release.jar signer --cles editeur.json --artefact box/imagunity-box.jar \
    --registre <dépôt>/deploy/livraison/registre-versions.json
#   → « Version 0.15.0 (n° 30) signée : box/imagunity-box.jar.manifeste.json » et « Registre mis à jour »
sh <dépôt>/deploy/box-systemd/construire-archive.sh box    # → imagunity-box-0.15.0.tar.gz, pour les nouvelles box
```

L'outil lit la version dans le jar, prend le numéro suivant du registre, vérifie les règles du § 2, signe, puis inscrit la version au registre. En cas de refus, rien n'est signé et le registre reste inchangé.

**Commiter le registre** (`git commit deploy/livraison/registre-versions.json`) : c'est la trace de la livraison, et le site central la vérifie avant de publier.

La phrase de passe de la clé se saisit au terminal, ou passe par `IMAGUNITY_PHRASE_EDITEUR`. Si elle contient des accents, il faut une locale UTF-8 (`LANG=C.UTF-8`).

## 5. Publier (site central)

Copier le dossier `box` signé sur le site central, puis :

```bash
cd /opt/imagunity && git pull                                 # registre à jour
sh deploy/site-central/publier-version.sh /chemin/box pilote  # quelques box d'abord
# après quelques jours sans incident sur les box pilotes :
sh deploy/site-central/publier-version.sh deploy/site-central/mises-a-jour/pilote general
```

Le script refuse de publier si :
- la clé de l'éditeur n'a pas l'empreinte `EMPREINTE_EDITEUR` du `.env` ;
- la signature est invalide ;
- la version n'est pas au registre avec le même numéro et la même empreinte.

Il dépose le jar avant le manifeste (`latest.json`) : une box ne voit jamais un manifeste sans son jar. Il utilise le Java de la machine, ou à défaut celui de l'image du relais, sans rien télécharger.

Les box du canal l'installent la nuit suivante ([installation d'une box, § 10](installation-box-rocky10.md)). Une version défectueuse ne se retire pas : on livre une version corrigée, de numéro supérieur.

## 6. Mettre à jour le site central depuis une livraison

```bash
gunzip -c site-central/images.tar.gz | docker load
# dans deploy/site-central/.env : IMAGUNITY_VERSION=0.15.0
docker compose up -d --no-build directory && docker compose up -d --no-build relay
```

L'annuaire passe avant le relais, et le site central avant les box, quand les notes de version le demandent.

## 7. Ce qui a été vérifié

Toute la chaîne a été déroulée sur une copie du dépôt :
- `fixer-version.sh 0.15.0`, puis construction : le jar porte la version 0.15.0, avec la console ;
- contrôles du workflow (étiquette, `pom.xml`, console) ;
- signature par le registre : n° 30 ;
- archive `imagunity-box-0.15.0.tar.gz` ;
- publication sur le canal `pilote`, puis promotion sur `general`.

Les refus aussi ont été vérifiés : mauvaise clé de l'éditeur, version absente du registre, manifeste altéré, version `-SNAPSHOT`, version déjà signée ou plus ancienne, `--version` ou `--sequence` en désaccord avec le jar ou le registre.

**Non vérifiés** :
- le workflow sur GitHub, à essayer par un essai à blanc ;
- la publication sans Java, avec l'image du relais : Docker Hub refusait les téléchargements pendant l'essai.
