# Films explicatifs : plan

> Objectif : de courtes vidéos (1 à 3 minutes) qui montrent comment ImagUnity fonctionne, de bout en bout, pour un public non spécialiste : directions d'établissement, DPO, équipes d'imagerie, responsables d'organisation, auditeurs.
> Statut : **réalisé** (novembre 2026) : les six films sont présentés dans [README.md](README.md).

## 1. Principes

- **Animations schématiques**, et non captures vidéo d'écran. Box, relais, annuaire et PACS sont représentés par des pictogrammes ; les examens, paquets, morceaux et messages par des objets qui se déplacent.
  - Les écrans réels (console de la box, interface du responsable) apparaissent en incrustation, à partir des captures du guide, quand ils aident.
- **Muets, sous-titrés en français**. Le texte est incrusté à l'image, une ou deux lignes par plan, assez lent pour être lu. Un fichier `.srt` accompagne chaque film pour une voix off ultérieure, ou une traduction.
- **Vrais messages, vrais formats**. Les noms, empreintes et messages affichés sont ceux du logiciel (« Demande d'enrôlement transmise à l'annuaire », « File de réception IU-T créée »…), avec des données **synthétiques** uniquement : organisation « chu-exemple », patients fictifs, aucun vrai DICOM.
- **Exactitude avant spectacle**. Chaque film suit la [spécification du protocole](../reference/specification-protocole.md) et les guides. Ce qui est simplifié est dit à l'écran (« simplifié : en réalité, deux relais reçoivent chaque morceau »).
- **Charte commune** :
  - mêmes pictogrammes, couleurs et typographie que la présentation et le schéma des composants du guide du responsable ;
  - code couleur constant : **vert** pour ce qui est chiffré, **orange** pour ce qui est signé, **rouge** pour un refus.
- **Format** : MP4 (H.264) 1920 × 1080, 30 images par seconde, quelques Mo par film, lisible partout. Une image de couverture par film.

## 2. Les films

### Film 1. ImagUnity en deux minutes (vue d'ensemble)

Pour tout public. Le problème, puis l'idée de la « poste restante aveugle ».

1. Deux hôpitaux, un examen à transmettre ; aujourd'hui : CD gravé, VPN, messagerie non sécurisée.
2. Les trois composants : une **box** par site, un **relais** qui garde des colis qu'il ne peut pas ouvrir, un **annuaire** qui dit qui est qui.
3. Le trajet en accéléré : PACS → box → relais → box → PACS, avec l'accusé qui revient.
4. Ce qui est garanti : chiffré de bout en bout, aucun port entrant, le relais ne voit rien, chaque box est enrôlée par le responsable.

### Film 2. Créer et gérer une organisation

Pour les responsables d'organisation.

1. La **clé du responsable** : créée une fois, gardée hors ligne, protégée par phrase de passe, avec son empreinte.
2. L'**annuaire** : pourquoi il n'est pas de confiance, et pourquoi tout y est signé. Ce que contient une entrée : clés, adresses, groupes, validité.
3. La vie courante dans l'interface locale :
   - tirer un code d'enrôlement ;
   - valider une demande ;
   - renouveler avant expiration ;
   - révoquer une box perdue.
4. Les **règles communes** : brouillon, signature hors ligne, publication, application par les box (configuration centralisée).

### Film 3. Comment une box s'enrôle

Pour les installateurs et les responsables.

1. Le responsable tire un **code** et le transmet hors bande ; l'installateur le recopie dans la configuration.
2. Au démarrage, la box crée son **identité** (clés de signature et de chiffrement, protégées par le TPM).
3. Elle dépose sa **demande signée** avec la **preuve du code**. L'annuaire ne peut ni lire le code ni fabriquer la preuve.
4. Le responsable vérifie et **signe l'entrée sur son poste**, puis la publie. Sans code : comparaison de l'empreinte au téléphone.
5. La box lit l'annuaire, se voit active, crée sa **file de réception** sur le relais. Les autres box la voient à leur lecture suivante ; l'appairage peut commencer.

### Film 4. Le voyage d'un examen

Le cœur technique, pour les équipes d'imagerie, les DSI et les auditeurs. Il peut être découpé en deux films si la durée dépasse trois minutes.

1. **PACS → box** : envoi DICOM (C-STORE) vers l'AET de la box ; la box reçoit les instances.
2. **Règles** : routage par champ DICOM vers le bon partenaire, priorité des séries (urgent d'abord).
3. **Empaquetage et chiffrement** : regroupement par étude, chiffrement de bout en bout pour la seule clé du destinataire.
4. **Fragmentation** : le paquet est découpé en **morceaux** déposés sur le relais de l'émetteur, chacun avec sa capacité de retrait. Ils sont copiés sur plusieurs relais si l'organisation en a.
5. **Annonce** : un message signé et chiffré est déposé dans la **file du destinataire**. Le relais ne voit ni le type ni le contenu.
6. **Retrait** : la box destinataire relève sa file, vérifie la signature d'après l'annuaire, retire les morceaux par capacité et vérifie leur empreinte. Elle bascule sur un autre relais si l'un manque.
7. **Restitution** : déchiffrement, vérification, envoi au PACS destinataire ; restitution au fil de l'eau, série par série.
8. **Accusé et effacement** : l'accusé signé revient ; les morceaux sont effacés du relais ; l'émetteur voit le transfert « livré ».

### Film 5. Quand quelque chose ne va pas

Pour les exploitants.

- Relais injoignable : nouvelles tentatives, bascule sur l'autre relais.
- Box destinataire éteinte : les morceaux attendent, dans la limite du bail.
- PACS figé : délais d'attente, le transfert reprend.
- Box révoquée : les autres box refusent d'échanger, avec le motif à l'écran.
- Message ou morceau altéré : refusé, rien n'est restitué.

### Film 6. Échanger entre organisations (fédération)

Après l'étape 1 de la fédération.

1. Deux régions, deux annuaires, deux relais.
2. La **reconnaissance mutuelle** : chaque responsable signe un accord, avec l'empreinte vérifiée au téléphone, un périmètre en groupes et des relais autorisés.
3. Un examen de A vers B : dépôt sur le relais de B, retrait sur le relais de A. Les relais ne se parlent pas.
4. Les refus : accord à sens unique, box hors périmètre, relais non autorisé, identifiant usurpé.

## 3. Réalisation

- **Sources dans le dépôt** (`docs/films/sources/`) :
  - une scène HTML/SVG par film, pilotée par une ligne de temps déclarative (plans, durées, sous-titres) ;
  - une bibliothèque commune de pictogrammes et d'animations.
- **Rendu reproductible** : un script Node (Playwright, Chromium sans écran) avance la ligne de temps image par image et capture chaque image ; ffmpeg assemble le MP4. Le même script produit l'image de couverture et le `.srt`. Un film se regénère d'une commande après une modification du logiciel.
- **Vérification** : relecture de chaque film image par image (planches contact) avant publication. Cohérence des messages affichés avec le code et les guides.
- **Livraison** :
  - MP4, couvertures et `.srt` dans `docs/films/` ;
  - une page `docs/films/README.md` qui les présente ;
  - liens depuis le README du dépôt, les guides et la présentation.

## 4. Ordre

1. Charte, pictogrammes et moteur de rendu, validés sur le film 1.
2. Films 4 et 3, les plus demandés.
3. Films 2 et 5.
4. Film 6, une fois l'étape 1 de la fédération réalisée et essayée.
