# Essai sur une station Windows

Scripts pour lancer, sur une seule station Windows, l'**annuaire**, le **relais** IU-T et une ou deux **box**, chacun dans sa fenêtre. Pour essayer et développer, avec des **données synthétiques uniquement** : comptes et mots de passe d'essai, liaisons en HTTP, écoute sur `localhost` seulement. Ce n'est pas une installation : voir les [guides](../../../docs/README.md).

| Script | Rôle |
|---|---|
| `config.bat` | Réglages communs : chemin de Java et de `psql`, ports, mots de passe d'essai, empreinte du responsable |
| `construire.bat` | Construit l'annuaire, le relais et la box (sans les tests) |
| `creer-bases.bat` | Crée, une fois, les bases PostgreSQL `relais` et `annuaire` et leurs comptes |
| `admin.bat` | Outil du responsable : clé de l'organisation, enrôlement, publication |
| `dev-annuaire.bat` | Annuaire sur http://localhost:8090 (administration : `/admin/`) |
| `dev-relais.bat` | Relais sur http://localhost:8443 |
| `dev-box.bat` · `dev-box.bat b` | Box A (console http://localhost:8081, DICOM 11112) · box B (http://localhost:8082, DICOM 11113) |

Les données (clé du responsable, identités et bases des box, morceaux du relais) vont dans `donnees\`, ignoré par Git. Pour repartir de zéro : arrêter les services, supprimer `donnees\`, vider les deux bases.

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
admin.bat organisation-creer --organisation essai --cles donnees\organisation.json
```

`organisation-creer` demande une phrase de passe (16 caractères au moins) et affiche l'**empreinte courte** du responsable. La recopier dans `config.bat` :

```bat
set "ORGANISATION_CODE=XXXX-XXXX-XXXX-XXXX-XXXX-XXXX"
```

Puis, chacun dans sa propre fenêtre et dans cet ordre :

```bat
dev-annuaire.bat
dev-relais.bat
dev-box.bat
dev-box.bat b
```

Au premier démarrage, chaque box crée son identité dans `donnees\box-a` ou `donnees\box-b`.

## Enrôler et appairer les box (à la main)

Ces étapes suivent le [guide d'installation d'une box, § 9](../../../docs/guides/installation-box-rocky10.md).

1. **Demande d'enrôlement** de chaque box : dans sa console, onglet Annuaire, « Télécharger la demande d'enrôlement ». Sans console :

   ```bat
   curl -u operateur:essai-synthetique -o demande-a.json http://localhost:8081/api/v1/directory/enrollment-request
   ```
2. **Signer, puis publier** l'entrée :

   ```bat
   admin.bat enroler --cles donnees\organisation.json --demande demande-a.json --sortie entree-a.json
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
