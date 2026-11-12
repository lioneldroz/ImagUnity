# Configuration centralisée des box (plan)

> Objectif : paramétrer chaque box (règles de priorité, règles de routage, puis d'autres réglages) depuis un serveur central de l'organisation, par une interface d'administration, au lieu de la configuration locale de chaque box.
> Statut : **plan, à réaliser plus tard** (décidé en novembre 2026, avant les règles de priorité). Les règles de priorité et de routage sont déjà décrites comme un document JSON au format prévu ici, et rechargeables sans redémarrage.

## 1. Principe

Le **service d'annuaire** est déjà le serveur central de l'organisation, et chaque box le lit régulièrement par connexion sortante ([annuaire signé](annuaire-signe.md)). La configuration y serait publiée de la même façon que les entrées de box :

```
Administrateur ──► interface d'administration ──► service d'annuaire (documents signés)
                                                         ▲
                       box A, box B… (lecture périodique, connexions sortantes seulement)
```

- **Documents de configuration signés**, publiés à côté des entrées d'annuaire : configuration d'une box ou d'un groupe de box.
- **Application par la box** à chaque synchronisation : vérification de la signature, de la cible et du numéro de version, puis application **sans redémarrage**. Document invalide (signature, règle incorrecte, version plus ancienne) : refusé, la configuration précédente reste en vigueur, l'incident est visible dans la console.
- **Rien de nouveau ne s'ouvre** sur les sites : la box tire sa configuration, le serveur ne pousse rien.

## 2. Document de configuration

```json
{
  "suite": "IU1",
  "organization": "chu-exemple",
  "target": { "boxes": ["<boxId>"], "groups": ["imagerie-nord"] },
  "sequence": 12,
  "issuedAt": "2027-01-15T10:00:00Z",
  "priority": {
    "default": 5,
    "rules": [
      { "name": "scanner-thorax", "priority": 1,
        "et": { "Modality": "CT", "SeriesDescription": "*THORAX*|*POUMON*" } },
      { "name": "localisateurs-en-dernier", "priority": 9,
        "ou": { "ImageType": "*LOCALIZER*", "SeriesDescription": "*SCOUT*|*TOPO*" } }
    ]
  },
  "routing": { "aet": "IU_AUTO", "rules": [ … ] }
}
```

- Même syntaxe de règles que la configuration locale : `et` (tous les champs), `ou` (un champ suffit), valeurs exactes, `*` et `?`, alternatives `|`, négation `!`.
- **Numéro de version croissant** par cible (`sequence`), comme les mises à jour signées : une box refuse un document plus ancien que celui qu'elle applique (pas de retour en arrière imposé par un serveur compromis).
- Signé sur ses octets exacts (suite IU1 : Ed25519 + ML-DSA-65), comme les entrées d'annuaire.
- **Préséance** : configuration centrale si elle existe, sinon configuration locale. Option de verrouillage local (une box de démonstration, par exemple) à décider.

## 3. Qui signe ? (décision à prendre d'abord)

La clé du responsable, qui signe l'annuaire, reste aujourd'hui **hors ligne**. Une interface web qui publie de la configuration suppose de choisir :

| Option | Principe | Avantages | Risques |
|---|---|---|---|
| **A. Clé de configuration déléguée** | Une clé dédiée, sur le serveur, autorisée par un document signé de la clé du responsable ; elle ne peut signer **que** de la configuration | Interface autonome, publication immédiate | Serveur compromis = configuration détournable (routage vers une box de l'organisation, priorités) ; à limiter par des garde-fous (§ 4) |
| **B. Signature dans le navigateur** | Clé de l'administrateur sur son poste (ou carte, clé matérielle) ; le serveur ne fait que stocker | Le serveur ne peut rien signer | Gestion des clés des administrateurs, prise en charge de ML-DSA dans le navigateur |
| **C. Validation en deux temps** | Édition et vérification dans l'interface, puis signature par le responsable (outil en ligne de commande, comme aujourd'hui) | Aucune clé en ligne | Publication moins immédiate |

Proposition de départ : **C** pour commencer (aucune clé en ligne, réutilise l'outil du responsable), puis **A** avec garde-fous si la fréquence des changements le justifie.

## 4. Garde-fous

- Le routage ne peut désigner **que des box actives de l'annuaire**, comme aujourd'hui : un document détourné ne peut pas envoyer d'examens hors de l'organisation.
- Journal d'audit côté serveur (qui a changé quoi, quand) et côté box (version appliquée, refus).
- Signalement dans la console de la box de tout changement de configuration, avec la version et son auteur.
- Retour à une version antérieure : nouvelle version (numéro supérieur) au contenu ancien, jamais un numéro plus petit.

## 5. Interface d'administration

- Liste des box et des groupes, configuration en vigueur et version appliquée par chaque box (remontée par la box lors de sa synchronisation, à prévoir).
- Édition des règles de priorité et de routage, avec **test d'une règle sur un exemple d'en-tête DICOM** (synthétique) avant publication.
- Ciblage : une box, un groupe, toute l'organisation.
- Historique des versions, comparaison, retour arrière.
- Comptes d'administration distincts du compte du responsable ; rôles (lecture, édition, publication).

## 6. Côté box

- Lecture de la configuration à chaque synchronisation de l'annuaire, vérification, application sans redémarrage (les règles sont déjà rechargeables).
- Console : configuration en vigueur, sa provenance (locale ou centrale), sa version, le dernier refus éventuel ; en lecture seule.
- Journaux : noms de règles et versions, jamais de valeurs lues dans les instances.

## 7. Étapes proposées

1. Décider de la signature (§ 3).
2. Format du document, vérification et application dans la box, avec tests et vecteurs.
3. Publication par l'outil du responsable (option C) et lecture par la box ; essai réel : changement de règles de priorité sans redémarrage.
4. Interface d'administration (édition, test, ciblage, historique).
5. Remontée de la version appliquée par chaque box ; console.
6. Option A (clé déléguée) si besoin.

## 8. Questions ouvertes

- Groupes de box : définis dans l'annuaire (attribut de l'entrée) ou dans le serveur de configuration ?
- Autres réglages à centraliser : seuils, durées de séjour, taille de morceau, comptes de la console (à exclure ?).
- Verrouillage local possible ou non.
