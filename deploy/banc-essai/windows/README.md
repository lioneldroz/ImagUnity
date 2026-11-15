# Essai sur une station Windows

Scripts pour lancer, sur une seule station Windows, l'**annuaire**, le **relais** IU-T et une ou deux **box**, chacun dans sa fenêtre. Pour essayer et développer, avec des **données synthétiques uniquement** : comptes et mots de passe d'essai, liaisons en HTTP, écoute sur `localhost` seulement. Ce n'est pas une installation : voir les [guides](../../../docs/README.md).

| Script | Rôle |
|---|---|
| `config.bat` | Réglages communs : chemin de Java et de `psql`, ports, mots de passe d'essai, empreinte du responsable |
| `construire.bat` | Construit l'annuaire, le relais et la box (sans les tests) |
| `creer-bases.bat` | Crée, une fois, les bases PostgreSQL `relais` et `annuaire` et leurs comptes |
| `interface.bat` | **Interface du responsable** dans le navigateur : crée l'organisation au premier lancement, puis guide pas à pas |
| `admin.bat` | Outil du responsable en ligne de commande, exécuté sur le poste du responsable : clé de l'organisation, enrôlement, publication |
| `deposer.bat` | Remet aux autres machines les fichiers du poste du responsable : clé publique (annuaire, relais, box), clé de fraîcheur (annuaire) |
| `dev-annuaire.bat` | Annuaire sur http://localhost:8090 (administration : `/admin/`) |
| `dev-relais.bat` | Relais sur http://localhost:8443 |
| `dev-box.bat` · `dev-box.bat b` | Box A (console http://localhost:8081, DICOM 11112) · box B (http://localhost:8082, DICOM 11113) |

## Une machine par dossier

Chaque composant tourne comme sur sa propre machine, dans son dossier de `machines\` (ignoré par Git), et ne lit que ses propres fichiers :

```
machines\
  poste-responsable\   organisation.json (clé privée), organisation.json.pub.json, codes.json,
                       fraicheur.json, delegation.json, demandes et entrées signées à la main
  annuaire\            config\organisation.pub.json, config\fraicheur\ ; travail\
  relais\              config\organisation.pub.json ; morceaux\, travail\
  box-a\, box-b\       config\organisation.pub.json ; donnees\ (identité, base, transferts)
```

Rien n'est partagé : les fichiers passent du poste du responsable aux autres machines par **`deposer.bat`**, qui joue l'exploitant du site central et les installateurs des box. Il copie la clé publique (renommée `organisation.pub.json`, comme sur un vrai site central) et, pour l'annuaire, la clé de fraîcheur. La clé privée et les codes d'enrôlement ne quittent jamais `poste-responsable\`. Sans sa copie de la clé, une machine refuse de démarrer et le dit. Chacune vérifie aussi l'empreinte de `config.bat` (`ORGANISATION_CODE`) : sur de vraies machines, chacune aurait sa propre copie, recopiée de vive voix.

Pour repartir de zéro : arrêter les services, supprimer `machines\`, vider les deux bases. Une station déjà installée avec l'ancien dossier `donnees\` : déplacer `donnees\organisation.json*`, `codes.json`, `fraicheur.json` et `delegation.json` dans `machines\poste-responsable\`, lancer `deposer.bat`, puis supprimer `donnees\` (les box repartent avec une nouvelle identité, à réenrôler).

## Prérequis

- **Java 25** (JDK) : `java -version` doit afficher 25. Sinon, renseigner `JAVA` dans `config.bat`.
- **PostgreSQL** pour Windows (version 15 ou plus), service démarré, avec le mot de passe de l'utilisateur `postgres`. Si `psql` n'est pas dans le `PATH`, renseigner `PSQL` dans `config.bat`, par exemple `C:\Program Files\PostgreSQL\17\bin\psql.exe`.
- Pour la console web de la box : **Node.js 24**. Sans lui, la box est construite avec son API seule.
- Pour un transfert de bout en bout : un **PACS d'essai** (Orthanc, par exemple) qui envoie vers la box et reçoit les restitutions. Ses réglages sont en tête de `dev-box.bat` (`PACS_*`).

## Premier lancement

Dans une invite de commandes (`cmd`), depuis ce dossier :

```bat
construire.bat
creer-bases.bat
interface.bat
```

`interface.bat` ouvre l'interface du responsable dans le navigateur. Sans organisation, elle s'ouvre sur l'onglet « Démarrage » : saisir l'identifiant (`essai`) et une phrase de passe (16 caractères au moins), puis « Créer l'organisation ». C'est l'équivalent de `admin.bat organisation-creer --organisation essai --cles organisation.json`, qui reste possible. Les fichiers sont créés dans `machines\poste-responsable\`. La page affiche l'**empreinte courte** du responsable et la ligne à recopier dans `config.bat` :

```bat
set "ORGANISATION_CODE=XXXX-XXXX-XXXX-XXXX-XXXX-XXXX"
```

Remettre ensuite la clé publique aux autres machines (l'empreinte est affichée de nouveau, pour comparaison) :

```bat
deposer.bat
```

Après avoir délégué la clé de fraîcheur dans l'interface (étape 4) : `deposer.bat annuaire`, puis relancer `dev-annuaire.bat`.

Puis, chacun dans sa propre fenêtre et dans cet ordre :

```bat
dev-annuaire.bat
dev-relais.bat
dev-box.bat
dev-box.bat b
```

Au premier démarrage, chaque box crée son identité dans `machines\box-a\donnees` ou `machines\box-b\donnees`.

## Enrôler les box automatiquement (code d'enrôlement)

```bat
admin.bat code-creer --codes codes.json --nom "Box d'essai A"
set CODE_ENROLEMENT=<code affiché>
dev-box.bat
```

La box envoie seule sa demande à l'annuaire. Puis, dans une autre fenêtre :

```bat
admin.bat enroler-attente --cles organisation.json --codes codes.json --annuaire http://localhost:8090
```

Ou, depuis le navigateur, avec l'interface locale du responsable : valider les demandes une à une (une demande sans code se valide après comparaison de l'empreinte), renouveler ou révoquer les box, tirer les codes d'enrôlement :

```bat
interface.bat
```

Même chose pour la box B (`set CODE_ENROLEMENT=…` avec un autre code, puis `dev-box.bat b`). Détails : [guide du responsable, § 2](../../../docs/guides/guide-responsable.md).

## Enrôler et appairer les box (à la main)

Ces étapes suivent le [guide d'installation d'une box, § 9](../../../docs/guides/installation-box-rocky10.md).

1. **Demande d'enrôlement** de chaque box : dans sa console, onglet Annuaire, « Télécharger la demande d'enrôlement », puis la remettre au responsable (la copier dans `machines\poste-responsable\`). Sans console :

   ```bat
   curl -u operateur:essai-synthetique -o machines\poste-responsable\demande-a.json http://localhost:8081/api/v1/directory/enrollment-request
   ```
2. **Signer, puis publier** l'entrée :

   ```bat
   admin.bat enroler --cles organisation.json --demande demande-a.json --sortie entree-a.json
   admin.bat publier --annuaire http://localhost:8090 --entree entree-a.json
   ```

   `enroler` affiche l'empreinte de la box, à comparer avec celle de sa console. Même chose pour la box B.
3. Attendre une trentaine de secondes : les box et le relais relisent l'annuaire toutes les 30 s dans cet essai. Chaque box crée alors sa file sur le relais.
4. **Appairer** : dans la console de la box A, onglet Annuaire, appairer la box B sous le nom `box-b`. La box B crée le contact de son côté ; l'appairer en retour sous le nom `box-a` si besoin.

Un examen envoyé par le PACS vers la destination `IU_TO_B` de la box A, sur le port 11112, part alors vers la box B, qui le restitue à son PACS.

## Limites

- Scripts écrits pour `cmd` sous Windows 10 ou 11, **non encore essayés sur une station Windows**. Signaler tout message d'erreur.
- Box lancées directement, sans le lanceur des mises à jour signées : pas de mise à jour automatique dans cet essai.
- Clés des box non protégées par phrase de passe ni par TPM : la box l'indique par un avertissement au démarrage.
