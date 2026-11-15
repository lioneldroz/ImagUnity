# Poste du responsable

Ce dossier suffit au responsable de l'organisation pour **créer son organisation** et la gérer au quotidien,
depuis une page de son navigateur, sans taper de commande.

## Ce qu'il faut

- Un poste Windows 10 ou 11 (ou Linux, macOS), de préférence réservé à cet usage et sans accès inutile.
- **Java 25** (par exemple Eclipse Temurin 25). Vérifier dans une invite de commandes : `java -version`.
- Ce dossier, avec `imagunity-directory.jar` (fourni dans la livraison, dossier `poste-responsable`).
- L'**adresse de l'annuaire** et le **mot de passe du compte de dépôt**, donnés par l'exploitant du site central.
  Pour un premier essai, sans site central, l'interface fonctionne aussi : elle signale seulement l'annuaire
  injoignable.

## Démarrer

1. Ouvrir `reglages.bat` avec le Bloc-notes et y mettre l'adresse de l'annuaire (`ANNUAIRE=...`). Enregistrer.
2. Double-cliquer sur `interface-responsable.bat`. Le navigateur s'ouvre sur l'interface.
3. Suivre l'onglet **Démarrage**, étape par étape :
   1. créer l'organisation (identifiant et phrase de passe) : la clé est créée sur ce poste ;
   2. transmettre la clé publique et l'**empreinte** à l'exploitant du site central ;
   3. se connecter à l'annuaire avec le compte de dépôt ;
   4. déléguer la clé de fraîcheur, et la transmettre à l'exploitant ;
   5. tirer un code d'enrôlement par box, et le transmettre à chaque installateur ;
   6. valider les box qui se présentent.
4. L'onglet **Aide** explique chaque fichier, l'empreinte, les groupes et que faire en cas de problème.

Laisser la fenêtre noire ouverte pendant l'utilisation. Pour arrêter : bouton « Quitter » de la page.

## Les fichiers de l'organisation

Ils sont créés dans `donnees\` à côté du lanceur (réglable dans `reglages.bat`, `DOSSIER=...`) :

| Fichier | Secret ? | À faire |
|---|---|---|
| `organisation.json` | **oui** (clé privée, chiffrée par la phrase de passe) | Ne quitte pas ce poste. Une sauvegarde au coffre. |
| `organisation.json.pub.json` | non (clé publique) | Au site central sous le nom `organisation.pub.json`, et dans chaque box. |
| `codes.json` | oui (codes d'enrôlement) | Reste ici, sauvegardé avec la clé. |
| `fraicheur.json`, `delegation.json` | à protéger | Au service d'annuaire, chaque mois. |

**Sauvegarder `donnees\` sur un support séparé dès la création de l'organisation**, et ranger la phrase de passe
ailleurs. Sans la clé ou sans la phrase, il faudrait recréer l'organisation et réenrôler toutes les box.

Idée simple et sûre : garder tout ce dossier (lanceur, jar et `donnees\`) sur une **clé USB dédiée**, branchée
seulement le temps des signatures, et une copie de cette clé au coffre.
