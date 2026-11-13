# Priorité des séries utiles au diagnostic

> Objectif (pilote, priorité 6 du [plan](plan-de-realisation.md)) : les séries utiles au diagnostic partent et sont restituées au PACS du destinataire **les premières**, sans attendre la fin de l'étude.
> Statut : **fait**, vérifié sur la pile Docker (§ 6). Les règles peuvent aussi être fixées depuis le service d'annuaire ([configuration centralisée](configuration-centralisee.md)) : elles l'emportent alors sur les règles locales.

## 1. Principe

- **Box émettrice** : chaque série reçoit une priorité, de **1** (la plus haute) à **9**, d'après des règles sur ses champs DICOM. L'empaquetage range les séries par priorité, et **un changement de priorité ouvre un nouveau paquet** : les premiers paquets ne contiennent que les séries prioritaires. Les paquets partent dans cet ordre.
- **Annonce dès le départ** : le transfert est annoncé au destinataire (annonce signée) dès que tous ses paquets sont chiffrés et confiés au transport, sans attendre la fin du dépôt.
- **Box destinataire** : chaque paquet reçu et vérifié est **restitué aussitôt** au PACS, sans attendre les autres. L'accusé de bout en bout part quand toute l'étude est restituée ; la console affiche « partielle : 1/2 paquets » entre-temps.
- **Relais** : il ne voit que des morceaux chiffrés ; il ignore tout des séries et de leur priorité.

Sans règle, toutes les séries ont la priorité par défaut et partent dans l'ordre d'arrivée, comme avant ; la restitution au fil de l'eau s'applique quand même aux études de plusieurs paquets.

## 2. Règles

Sur la box émettrice, dans sa configuration ou dans un fichier JSON :

```yaml
imagunity:
  priority:
    default-priority: 5            # séries qu'aucune règle ne couvre
    rules:                         # lues dans l'ordre : la première qui correspond s'applique
      - name: scanner-thorax
        priority: 1
        and:                       # tous les champs doivent correspondre
          Modality: CT
          SeriesDescription: "*THORAX*|*POUMON*"
      - name: localisateurs-en-dernier
        priority: 9
        or:                        # un seul champ suffit
          ImageType: "*LOCALIZER*"
          SeriesDescription: "*SCOUT*|*TOPO*"
```

- **Champs** : mots-clés du dictionnaire DICOM (`Modality`, `SeriesDescription`, `BodyPartExamined`, `ImageType`, `ProtocolName`…), sans tenir compte de la casse.
- **Motifs** :

| Motif | Correspond à |
|---|---|
| `CT` | exactement `CT` |
| `CHU*` | commence par `CHU` |
| `*THORAX*` | contient `THORAX` |
| `M?` | `M` suivi d'un caractère |
| `*THORAX*\|*POUMON*` | l'un ou l'autre |
| `!SR\|KO` | ni `SR` ni `KO` |
| `""` / `"!"` | champ absent / présent |

  La casse et les espaces autour de la valeur sont ignorés ; un champ à plusieurs valeurs (`ImageType`) correspond si l'une d'elles correspond.
- **Combinaisons** : `and` (tous), `or` (un seul suffit), ou les deux : tous ceux de `and` et au moins un de `or`. Pour (A et B) ou (C et D), deux règles de même priorité.
- **Priorité d'une série** : évaluée sur l'en-tête de chacune de ses instances (lu sans les pixels) ; si elles divergent, la plus haute l'emporte.
- **Vérifications au démarrage** : mot-clé inconnu, priorité hors de 1 à 9, nom invalide ou en double : la box refuse de démarrer, avec un message explicite.

**Variables d'environnement** (Compose) :

```yaml
IMAGUNITY_PRIORITY_RULES_0_NAME: scanner-thorax
IMAGUNITY_PRIORITY_RULES_0_PRIORITY: "1"
IMAGUNITY_PRIORITY_RULES_0_AND_MODALITY: CT
IMAGUNITY_PRIORITY_RULES_0_AND_SERIESDESCRIPTION: "*THORAX*|*POUMON*"
```

Exemple d'essai : `deploy/campagne/compose.priorite.yml`.

**Fichier JSON rechargé à chaud** (`IMAGUNITY_PRIORITY_FILE`), au format du futur document de configuration centralisée :

```json
{ "default": 5,
  "rules": [ { "name": "scanner-thorax", "priority": 1,
               "and": { "Modality": "CT", "SeriesDescription": "*THORAX*|*POUMON*" } },
             { "name": "localisateurs-en-dernier", "priority": 9,
               "or": { "ImageType": "*LOCALIZER*", "SeriesDescription": "*SCOUT*|*TOPO*" } } ] }
```

Le fichier est relu dès qu'il change (vérifié avant chaque empaquetage), sans redémarrer la box. Un fichier devenu invalide est refusé : les règles précédentes restent en vigueur et l'erreur s'affiche dans la console. Un fichier invalide au démarrage empêche la box de démarrer.

## 3. Console

Carte « Priorité des séries » (lecture seule) : provenance des règles (fichier, configuration ou aucune règle), date de chargement, dernier refus éventuel, priorité par défaut, et chaque règle avec sa priorité et ses conditions. API : `GET /api/v1/priority`. Les règles ne contiennent que des motifs de configuration ; les journaux ne montrent jamais de valeur lue dans les instances.

## 4. Les mêmes conditions pour le routage

Les [règles de routage](routage-dicom.md) acceptent la même syntaxe : `and`, `or`, valeurs possibles avec `|`. L'ancien `when` reste accepté (même sens que `and`).

## 5. Limites

- La box émettrice attend toujours la **fin de l'étude** (aucune nouvelle instance pendant le délai d'inactivité) avant d'empaqueter : la priorité joue sur l'ordre d'envoi et de restitution, pas encore sur un envoi anticipé des séries prioritaires pendant que le PACS envoie la suite.
- Une restitution partielle laisse des instances dans le PACS du destinataire si un paquet suivant échoue ; l'accusé signale l'échec et le transfert se relance depuis la console de l'émetteur (les instances déjà restituées sont simplement renvoyées).
- Règles fixées par la box, ou depuis le service d'annuaire ([configuration centralisée](configuration-centralisee.md)).

## 6. Vérifié en réel (pile Docker, novembre 2026)

Pile de la campagne (box en version signée 0.10.0, transport IU-T, liaison bridée à **20 Mbit/s** en sortie de chaque box et du relais), scénario `priorite` : étude CT synthétique de **4 séries, 190 instances, 95 Mo**, dont une petite série `THORAX` (10 instances) **arrivée en dernier**. Règle de la box A : `thorax : priorité 1 (Modality=CT, SeriesDescription=*THORAX*|*POUMON*)` ([`compose.priorite.yml`](../deploy/campagne/compose.priorite.yml)).

| | Sans règle | Avec la règle |
|---|---|---|
| Paquets | 1 | 2 (priorités 1 puis 5) |
| Série THORAX complète chez Orthanc B | 61,0 s | **13,3 s** (−78 %) |
| Étude entière restituée et accusée | 61,6 s | 61,3 s |
| Vérification | 190/190 identiques | 190/190 identiques |

La box B restitue le paquet prioritaire (10 instances) dès sa réception, puis le reste ; l'accusé part à la fin. Résultats bruts : [sans règle](../deploy/campagne/resultats/campagne-20261112-213100.json), [avec la règle](../deploy/campagne/resultats/campagne-20261112-213223.json).
