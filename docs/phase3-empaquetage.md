# Phase 3 — Empaquetage

> Objectif : transformer une étude reçue en un ou plusieurs paquets prêts à transporter, et vérifier à l'arrivée qu'elle est complète et identique.
> Statut : **fait**, avec allers-retours empaquetage → dépaquetage testés, et un paquet réel ouvert avec le `tar` standard de Windows.

## 1. Format d'un paquet

Un paquet est un fichier **`<groupe>-<i>-of-<n>.tar.zst`** : une archive **tar POSIX** standard, compressée **sans perte** par **zstd**.

```
instances/<Series UID>/<SOP UID>.dcm   fichiers DICOM, octet pour octet, tels que reçus du PACS
manifest.json                          manifeste, dernière entrée
```

Il reste lisible sans ImagUnity, ce qui facilite le diagnostic et l'audit :

```bash
tar -xf 9d4494bc-…-1-of-1.tar.zst
```

(vérifié avec le `tar` de Windows 11, bsdtar 3.8.9 ; sous Linux : `zstd -d paquet.tar.zst && tar xf paquet.tar`).

### Manifeste

```json
{
  "format" : "imagunity-package",
  "version" : 1,
  "packageGroupId" : "9d4494bc-3c46-4b3c-bd91-27521be668ff",
  "partIndex" : 1,
  "partCount" : 1,
  "studyInstanceUid" : "2.25.7",
  "createdAt" : "2026-11-11T14:15:00.788562100Z",
  "studyTotals" : { "instances" : 1, "bytes" : 200 },
  "series" : [ {
    "seriesInstanceUid" : "2.25.7.1",
    "instances" : [ {
      "sopInstanceUid" : "2.25.7.1.1",
      "sopClassUid" : "1.2.840.10008.5.1.4.1.1.2",
      "transferSyntaxUid" : "1.2.840.10008.1.2.1",
      "path" : "instances/2.25.7.1/2.25.7.1.1.dcm",
      "size" : 200,
      "sha256" : "…"
    } ]
  } ]
}
```

- Les **identifiants DICOM** (étude, série, instance) sont conservés tels quels.
- Chaque instance porte son **empreinte SHA-256** : l'accusé de bout en bout (phase 6) pourra attester que l'examen est arrivé complet et identique, pas seulement téléchargé.
- `studyTotals` donne les totaux de l'étude entière : le destinataire sait quand il a reçu tous les paquets d'un même `packageGroupId`.
- Le manifeste est écrit **en dernier** : les empreintes sont calculées pendant l'écriture, en une seule lecture des instances.
- Le paquet entier a aussi une empreinte SHA-256 (`PackagePart.sha256`), à comparer de bout en bout après le transport.

## 2. Découpage

Au-delà de **900 Mo** de contenu (par défaut, sous la limite de 1 Go de XFTP mesurée en phase 1), l'étude est répartie en plusieurs paquets :

1. une série qui tient dans un paquet n'est **jamais coupée** ;
2. les séries sont prises dans l'ordre d'arrivée et regroupées tant que le paquet n'est pas plein ;
3. une série plus grande qu'un paquet est coupée **entre deux instances** ;
4. une instance plus grande qu'un paquet forme à elle seule un paquet.

La limite porte sur la taille avant compression : c'est prudent, car les images déjà compressées (JPEG 2000, par exemple) ne gagnent presque rien avec zstd.

## 3. Vérifications au dépaquetage

Le paquet vient d'un partenaire : `PackageReader` le traite comme une donnée **non fiable** et refuse :

| Refus | Pourquoi |
|---|---|
| Nom d'entrée autre que `instances/<UID>/<UID>.dcm` ou `manifest.json` | Empêche l'écriture hors du dossier cible (`../`, chemins absolus, UID forgés). |
| Entrée qui n'est pas un fichier ordinaire (dossier, lien symbolique, lien physique, fichier spécial) | Un lien pourrait viser un fichier hors du dossier cible. |
| Instance en double, entrée après le manifeste, manifeste absent | Le contenu doit être exactement celui que le manifeste décrit. |
| Manifeste illisible, de plus de 16 Mio, d'un autre format ou d'une autre version | Format non pris en charge ou manifeste forgé. |
| Volume décompressé au-delà de la limite configurée | Protection contre les « bombes » de décompression. |
| Instance absente du paquet, absente du manifeste, de taille ou d'empreinte différente | L'examen doit arriver complet et identique. |
| Fichier corrompu ou qui n'est pas du zstd | Paquet abîmé pendant le transport. |

En cas de refus, **tous les fichiers déjà extraits sont supprimés**, et un fichier existant n'est jamais écrasé.

## 4. Tests

| Test | Ce qu'il vérifie |
|---|---|
| `StudyPackagerTest` (9 tests) | Aller-retour octet pour octet ; contenu du manifeste ; compression effective sur des données compressibles ; découpage d'une étude en deux paquets et reconstitution complète ; aucun paquet partiel en cas d'échec ; UID invalides refusés. |
| `PackageReaderTest` (23 tests) | Tous les refus du § 3, dont traversée de chemin, liens, bombe de décompression, fichier corrompu. |
| `PartPlannerTest` (6 tests) | Règles de découpage du § 2. |
| Essai manuel | Paquet réel produit par `StudyPackager`, ouvert avec `tar -xf` (bsdtar 3.8.9) : instance identique, manifeste lisible. |

Couverture du module : 96,7 % des lignes, 92,2 % des branches.

**Défaut trouvé par les tests et corrigé** : pour Commons Compress, une entrée « lien symbolique » est considérée comme un fichier (`isFile()`). Le lecteur vérifie désormais le type exact de l'entrée.

## 5. Changement connexe

La validation des UID DICOM (`DicomUids`) passe du module `dicom` au module `domain` : l'entrée DICOM et l'empaquetage l'utilisent tous deux.

## 6. Ce qui reste

- **Branchement** : l'orchestrateur (phase 4) appellera `StudyPackager` sur chaque étude reçue, puis confiera les paquets au transport.
- **Adaptation au réseau** (niveau de compression, taille des paquets selon la liaison, ordre des séries) : réglages en place (`PackagingConfig`), politique à définir après la preuve de concept.
- **Volumétrie** : non mesurée sur des études de plusieurs Go.
