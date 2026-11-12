# Routage par champ DICOM

> Objectif (pilote, priorité 5 du [plan](plan-de-realisation.md), premier volet) : la box choisit elle-même le partenaire destinataire d'après le **contenu de l'examen** (modalité, établissement, description…). Le PACS n'a plus à connaître une destination DICOM par partenaire.
> Statut : **fait**, et vérifié sur la pile Docker (§ 5). Second volet, la réception STOW-RS (DICOMweb), à faire.

## 1. Deux façons de désigner le destinataire

| Destination DICOM | Destinataire |
|---|---|
| **Route fixe**, par exemple `IU_TO_B` | Toujours le même partenaire (comme avant) |
| **Destination de routage**, par exemple `IU_AUTO` | Choisi par des **règles** sur les champs de chaque instance |

Le PACS déclare une seule destination, `IU_AUTO`, et y envoie tout ce qui doit partir. Les routes fixes restent disponibles à côté.

## 2. Règles

```yaml
imagunity:
  dicom:
    routing-aet: IU_AUTO
    rules:
      - name: scanner-urgences
        when: {Modality: CT, InstitutionName: "CHU*"}
        to: [box-b]
      - name: irm
        when: {Modality: MR}
        to: [box-b, box-c]
      - name: tout-le-reste-sauf-sr
        when: {Modality: "!SR"}
        to: [box-b]
```

- Les règles sont lues **dans l'ordre**, et **la première qui correspond s'applique**.
- Une règle peut désigner **plusieurs partenaires** : l'étude part vers chacun, en un transfert distinct par partenaire, chacun avec son suivi, son accusé et sa relance.
- **Conditions** : un mot-clé DICOM du dictionnaire standard (`Modality`, `InstitutionName`, `StudyDescription`, `ReferringPhysicianName`, `BodyPartExamined`…), associé à un motif.
  - Dans un motif, `*` remplace n'importe quelle suite de caractères et `?` un seul caractère.
  - La casse et les espaces autour de la valeur sont ignorés, et un `!` initial inverse la condition.
  - Toutes les conditions d'une règle doivent être remplies.
  - Un champ à plusieurs valeurs (`ImageType`) convient si l'une d'elles correspond.
  - Un champ absent vaut la chaîne vide : `""` retient les instances sans ce champ, `"!"` celles qui l'ont.
  - Une règle sans condition retient tout.
- **Aucune règle ne correspond** : l'instance est **refusée** au PACS (statut `0110H`, « Aucune règle de routage ne correspond à cette instance »). Le PACS la garde en échec, visible par l'opérateur, au lieu qu'elle disparaisse ou parte au mauvais endroit. Orthanc affiche ce refus comme « Error in the network protocol » ; la cause exacte est dans le journal de la box.
- **Vérifications au démarrage**, avec refus de démarrer et un message explicite en cas d'erreur :
  - mot-clé inconnu (par exemple `Modalite`) ;
  - nom de partenaire invalide, ou destinataire en double ;
  - deux règles de même nom ;
  - règles sans destination de routage, ou l'inverse ;
  - destination de routage qui est aussi une route fixe.

  Le journal de démarrage résume les règles, par exemple « scanner-urgences (Modality=CT, InstitutionName=CHU*) → box-b ».

**Avec Compose**, les règles passent aussi par variables d'environnement. Les mots-clés y sont reconnus sans tenir compte de la casse :

```yaml
IMAGUNITY_DICOM_ROUTING_AET: IU_AUTO
IMAGUNITY_DICOM_RULES_0_NAME: scanner-urgences
IMAGUNITY_DICOM_RULES_0_WHEN_MODALITY: CT
IMAGUNITY_DICOM_RULES_0_WHEN_INSTITUTIONNAME: "CHU*"
IMAGUNITY_DICOM_RULES_0_TO_0: box-b
```

Exemple complet : `deploy/campagne/compose.routage.yml`.

## 3. Fonctionnement

- Les règles sont évaluées **à la réception de chaque instance**, sur son en-tête, sans lire les pixels. Une condition sur un champ propre à la série (par exemple `Modality`) peut donc répartir les séries d'une même étude entre partenaires : par exemple, les comptes rendus `SR` vers un archivage. Pour router toute l'étude d'un bloc, on utilise des champs propres à l'étude (`InstitutionName`, `StudyDescription`, `AccessionNumber`…).
- **Une copie par partenaire.** Le dossier de réception range les instances par destination DICOM **et** par partenaire : `<AET>/<partenaire>/<étude>/<série>/`. Une instance destinée à deux partenaires y figure deux fois, par lien physique quand c'est possible, sinon par copie. Chaque transfert supprime ses propres fichiers.
- **Reprise après redémarrage** : le partenaire est lu dans le chemin. Les instances en attente rangées par une version antérieure (sans partenaire dans le chemin) sont reprises avec la route fixe de leur destination DICOM.
- **Un transfert est identifié** par l'étude, la destination DICOM **et** le partenaire. La même étude routée vers deux partenaires donne deux transferts indépendants.
- **Données de patient** : les règles peuvent porter sur n'importe quel champ, mais les journaux ne montrent que le nom des règles et les partenaires, jamais les valeurs lues dans les instances.

## 4. Limites

- **Pas de règle sur des combinaisons** (OU entre conditions, comparaisons de dates ou de nombres). On écrit plusieurs règles dans l'ordre voulu.
- **Règles fixées par la configuration**, au démarrage. Elles ne se modifient pas encore depuis la console.
- Un partenaire désigné mais non appairé fait échouer **son** transfert (« Partenaire non appairé ») ; les autres partenaires de la même règle ne sont pas touchés. Une fois le partenaire appairé, le transfert se relance depuis la console.

## 5. Vérifié en réel (pile Docker, novembre 2026)

Sur la chaîne de la campagne de tests, avec des études synthétiques envoyées par Orthanc A vers la destination `IU_AUTO`, et les règles `scanner-chu (Modality=CT, InstitutionName=CHU*) → box-b` et `irm-deux-sites (Modality=MR) → box-b, box-c` :
- **scanner du CHU** (10 instances) : routé vers box B, transfert ACKNOWLEDGED, **10/10 identiques** chez B ;
- **échographie d'une clinique**, qu'aucune règle ne couvre : C-STORE refusé (« Aucune règle de routage ne correspond à cette instance »), aucun transfert créé ;
- **IRM** (5 instances) : **deux transferts**. Celui vers box B est ACKNOWLEDGED, avec **5/5 identiques**. Celui vers box C est en échec « Partenaire non appairé : box-c », car C n'existe pas dans cet environnement ;
- la route fixe `IU_TO_B` fonctionne toujours, à côté de la destination de routage.

## 6. Reste à faire

- **Réception STOW-RS (DICOMweb)** : seconde porte d'entrée, qui passera par les mêmes règles.
- Affichage des règles dans la console, avec un **essai à blanc** sur une instance déposée.
- Opérateurs supplémentaires si le besoin apparaît : OU, plages de dates, expressions régulières.
