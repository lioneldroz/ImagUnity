# Fédération entre organisations

> Objectif : permettre à des box d'**organisations différentes** (régions, pays) d'échanger des examens, sans réseau unique ni autorité centrale imposée, et sans affaiblir les garanties actuelles (relais aveugles, chiffrement de bout en bout, aucun port entrant).
> Statut : **proposition de conception** (novembre 2026), rien n'est mis en œuvre. Décisions à prendre : § 4 (modèle de confiance), § 8 (questions ouvertes). Recommandation : **reconnaissance mutuelle signée entre organisations** (option B, § 4), **sans relier les relais entre eux** (§ 3).

## 1. Point de départ

Aujourd'hui, **l'organisation est l'unique cercle de confiance**, dans le code comme dans les documents :

- une box épingle **une** clé de responsable et lit **un** annuaire (`IMAGUNITY_DIRECTORY_ORGANIZATION_KEY`, `IMAGUNITY_DIRECTORY_URL`) ;
- une entrée d'une autre organisation est une **erreur de sécurité**, pas un état ([annuaire signé § 3](annuaire-signe.md), règle 2) ;
- le relais n'épingle lui aussi qu'**une** clé d'organisation (`IMAGUNITY_RELAY_ORGANIZATION_KEY`). La [spécification § 6.4](specification-protocole.md) prévoit pourtant qu'il serve « chaque organisation » ;
- la note d'architecture prévoit qu'« une même box peut appartenir à plusieurs organisations », ce qui n'est pas réalisé.

Une organisation régionale suffit donc pour un réseau sous une seule autorité. Au-delà, deux box de deux organisations ne peuvent pas se parler.

## 2. Ce qui marche déjà entre relais différents

Le protocole ne suppose **pas** que l'émetteur et le destinataire utilisent le même relais :

| Étape | Où | Qui s'y connecte |
|---|---|---|
| Morceaux du paquet (IU-FILE) | relais **de l'émetteur** (`IMAGUNITY_IU_RELAYS`) | émetteur (dépôt), destinataire (retrait par capacité, sans contrôle d'identité) |
| Annonce et accusé (IU-MSG) | file **du destinataire**, sur **son** relais (adresse dans son entrée d'annuaire) | émetteur (dépôt signé), destinataire (relève) |

Une box de la région A qui écrit à une box de la région B se connecte donc directement au relais de B pour déposer l'annonce. La box de B télécharge les morceaux sur le relais de A. Tout reste en connexions sortantes.

Il manque deux choses, et aucune ne concerne les relais entre eux :

1. **la confiance** : la box de B doit accepter l'entrée signée par le responsable de A, et inversement ;
2. **le dépôt sur un relais étranger** : le relais de B doit accepter qu'une box de A dépose un message dans une file de B, donc reconnaître les box enrôlées de A.

## 3. Relier les relais entre eux : non, en tout cas pas comme fondation

L'idée serait de faire suivre les données de relais en relais, à la manière du courrier électronique (SMTP) : chaque box ne parlerait qu'au relais de sa région, et ce relais transmettrait au relais de la région destinataire. On l'écarte comme mécanisme de base.

| Critère | Relais reliés entre eux | Box qui vont directement au relais du partenaire (recommandé) |
|---|---|---|
| Rôle du relais | Devient un **routeur avec état** : files de transit, nouvelles tentatives, routage entre organisations | Reste un **stockage aveugle**, comme aujourd'hui |
| Métadonnées | Chaque relais traversé voit passer volumes et horaires, et le relais de sortie sait vers quelle organisation partent les données | Seuls les relais de l'émetteur et du destinataire voient le trafic |
| Stockage | Les morceaux sont copiés de relais en relais (double stockage, double effacement à garantir) | Une seule copie, sur le relais de l'émetteur, effacée au retrait |
| Confiance entre exploitants | Chaque paire de relais doit s'authentifier, et chaque exploitant dépend de la disponibilité des autres | Aucun accord technique entre relais |
| Responsabilité juridique | Floue : quel exploitant détient la donnée en transit, et dans quel pays ? | Claire : la donnée est chez l'exploitant de l'émetteur jusqu'au retrait |
| Pare-feu des établissements | **Atout** : une seule adresse sortante à autoriser | Une adresse par région partenaire à autoriser |
| Code | Nouveau protocole relais-relais, à concevoir, sécuriser et auditer | Peu de choses : confiance multi-organisations (§ 5) |

Le seul vrai avantage des relais reliés concerne le pare-feu. Il se traite plus tard, sans toucher au modèle, par un **relais de sortie** facultatif (§ 7, étape 5).

## 4. Modèle de confiance : trois options

| Option | Principe | Pour | Contre |
|---|---|---|---|
| A. Box multi-organisations | La box épingle plusieurs clés de responsable et s'enrôle dans chaque organisation | Proche de la note d'architecture, aucun nouveau document | Chaque box s'enrôle N fois (empreinte vérifiée hors bande chaque fois), chaque organisation tient l'entrée de la box. Ne passe pas à l'échelle : un CHU qui échange avec 30 régions s'enrôle 30 fois |
| **B. Reconnaissance mutuelle signée** (recommandée) | Le responsable de A signe un document qui **reconnaît** l'organisation B (sa clé, son annuaire, un périmètre) ; B fait de même pour A | Chaque box n'épingle que **sa** clé de responsable. Les clés étrangères lui arrivent signées par son propre responsable. Accord bilatéral, révocable, borné. Chaque organisation reste souveraine | Nombre d'accords en N² dans un grand réseau, sans hiérarchie |
| C. Racine de fédération | Une autorité (nationale, européenne) signe les clés des responsables ; les box épinglent la racine | Un seul geste par organisation, lisible pour les autorités | Point critique unique : une racine compromise ouvre tout le réseau. Gouvernance lourde, et un cercle de confiance trop large par défaut |

**Recommandation : B**, conçue pour accueillir C plus tard. Une reconnaissance peut être signée par un responsable (B) ou, si un jour une autorité de fédération existe, par cette autorité et acceptée par le responsable (C). Le document et sa vérification restent les mêmes ; seule change la clé qui le signe. Le périmètre de chaque accord (§ 5.1) évite le « tout le monde parle à tout le monde » de C.

## 5. Option B en détail

### 5.1 Document de reconnaissance

Signé par la clé du responsable (Ed25519 + ML-DSA-65, signature des octets exacts, comme les entrées), publié sur l'annuaire **de l'organisation qui reconnaît** :

```json
{
  "suite": "IU1",
  "type": "recognition",
  "organization": "grand-est",
  "partner": {
    "organization": "bourgogne-fc",
    "key": { "ed25519": "<X.509 base64>", "mlDsa65": "<X.509 base64>" },
    "fingerprint": "TVF3-WIAA-CQ6V-MZGC-HKTQ-5WEH",
    "directory": "https://annuaire.bourgogne-fc.example/directory/v1"
  },
  "scope": { "localGroups": ["imagerie-nord"], "partnerGroups": ["neurochirurgie"] },
  "serial": 1,
  "validFrom": "2026-12-01T00:00:00Z",
  "validUntil": "2027-03-01T00:00:00Z",
  "revoked": false
}
```

- **Empreinte vérifiée hors bande** entre les deux responsables, une fois par accord (et non une fois par box).
- **Périmètre** : groupes locaux autorisés à échanger avec l'organisation partenaire, et groupes du partenaire acceptés. Absent : toute l'organisation. On réutilise les groupes signés de la [configuration centralisée](configuration-centralisee.md).
- **Numéro, validité courte, révocation** : mêmes règles que les entrées (pas de retour en arrière, réémission pour renouveler ou révoquer).
- **Identifiant d'organisation** : unique dans un cercle de confiance, mais pas dans le monde. Deux organisations peuvent toutes deux s'appeler `chu-exemple`. La box identifie donc un partenaire par l'**empreinte de sa clé** ; le nom n'est qu'un libellé.

### 5.2 Règles de vérification (côté box)

Une entrée étrangère, signée par l'organisation P, n'est retenue que si :

1. la box détient une reconnaissance de P **active**, signée par son propre responsable ;
2. l'entrée est signée par la clé de P **figurant dans cette reconnaissance**, et son organisation est P ;
3. les règles actuelles s'appliquent : validité, révocation, pas de retour en arrière ;
4. la box locale et l'entrée étrangère sont dans le périmètre (groupes).

La réciprocité n'est pas vérifiée explicitement : une box de B refuse les messages d'une box de A tant que B n'a pas reconnu A. Un échange ne marche donc que si les deux responsables ont signé. La console signale un accord à sens unique (« A reconnaît B, réponse de B attendue »).

### 5.3 Relais

- Le relais épingle la clé **de son organisation** et lit ses reconnaissances. Il accepte le **dépôt de messages** des box actives des organisations reconnues, dans les files de ses propres box. Il ne le fait pas pour les morceaux : une box dépose toujours ses morceaux sur les relais de sa propre organisation (§ 2).
- Les quotas par box déposante (§ 6.4 de la spécification) s'appliquent tels quels aux box étrangères. Les limites de débit (pré-audit, constat R4) deviennent nécessaires avant toute ouverture.
- Le retrait des morceaux reste sans contrôle d'identité : une box de B retire sur le relais de A par capacité. **Rien ne change pour le relais de A**.

### 5.4 Annuaires : découverte et taille

Aujourd'hui, chaque box lit **toutes** les entrées de son organisation (`GET /directory/v1/entries`), environ 7,5 Ko chacune, toutes les 5 minutes par défaut. Pour 10 000 box, cela ferait 75 Mo par lecture et par box, et l'annuaire public laisserait aussi énumérer tout le réseau. Avec la fédération :

- **synchronisation par différence** : `GET /entries?since=<curseur>` ne renvoie que les entrées modifiées. C'est utile dès maintenant pour l'annuaire local ;
- **annuaires étrangers lus à la demande** : seulement les entrées des box du périmètre, ou d'une box précise (`GET /entries/{boxId}`), avec mise en cache et nouvelle vérification de leur validité ;
- **lecture réservée** aux box enrôlées des organisations reconnues (requêtes signées, piste déjà notée dans [annuaire signé](annuaire-signe.md)). Un annuaire fédéré ne doit pas devenir un annuaire public des établissements.

### 5.5 Révocation entre organisations

Le constat C2 du [pré-audit](../audit/pre-audit.md) s'aggrave avec la fédération : la box de B ne lit l'annuaire de A que de loin en loin, et un annuaire compromis peut retenir une révocation. Il faut une **preuve de fraîcheur** :

- une **clé de fraîcheur** en ligne, déléguée par le responsable (qui la signe, avec une validité courte), signe à intervalle régulier un état daté de l'annuaire (numéros les plus hauts, révocations) ;
- la box refuse d'**envoyer** vers une organisation dont la dernière preuve de fraîcheur est trop ancienne (seuil réglable, par exemple 24 h). C'est visible dans la console.

C'est le même mécanisme que celui qui manque déjà pour C2 : on le fait une fois, pour l'annuaire local et pour les annuaires étrangers.

### 5.6 Versions des box

La [spécification § 8.4](specification-protocole.md) suppose que « toutes les box d'une organisation se mettent à jour ensemble ». Entre organisations, ce n'est plus possible : chacune a son rythme de mise à jour, voire son propre serveur de mises à jour. Il faut donc :

- annoncer dans l'entrée d'annuaire les **versions du protocole** que la box comprend (par exemple `"protocols": ["iu1.0", "iu1.1"]`) ;
- faire choisir à l'émetteur la plus haute version commune, et refuser proprement l'envoi s'il n'y en a pas, avec un message visible dans la console ;
- tenir une règle de compatibilité : un changement de format sans version annoncée est interdit.

## 6. Ce que ça change dans le code

| Module | Changement |
|---|---|
| `directory` | Document de reconnaissance (format, signature, vérification) ; règles du § 5.2 au lieu de « organisation = celle de la box » ; preuve de fraîcheur |
| `directory-server` | Publication des reconnaissances ; synchronisation par différence ; lecture réservée ; signature des états de fraîcheur |
| `release` (outil du responsable) | Commandes `reconnaitre`, `retirer-reconnaissance`, affichage de l'empreinte du partenaire à confirmer ; écran dans l'interface locale |
| `app`, `transport` (`IuDirectory`) | Recherche d'un partenaire dans plusieurs annuaires ; cache des entrées étrangères ; organisation du partenaire dans l'appairage |
| `relay` | Dépôt de messages ouvert aux box des organisations reconnues ; limites de débit |
| `configuration`, `rules` | Routage DICOM vers des box étrangères ; périmètre appliqué à l'envoi |
| `api`, `frontend` | Organisation et état de l'accord pour chaque partenaire ; accords à sens unique ; fraîcheur des annuaires étrangers |
| `protocol` | Versions annoncées et choix de la version commune (§ 5.6) |

## 7. Étapes proposées

1. **Relais multi-organisations et reconnaissance** (§ 5.1 à 5.3) : deux organisations sur le banc d'essai, deux annuaires et deux relais. Une box de A envoie à une box de B, l'examen arrive intact, et l'accusé revient. Un accord révoqué bloque les nouveaux envois.
2. **Annuaires** (§ 5.4) : synchronisation par différence, lecture à la demande, lecture réservée.
3. **Fraîcheur et révocation** (§ 5.5) : ferme aussi le constat C2.
4. **Versions** (§ 5.6) : nécessaire avant la première fédération réelle entre organisations aux mises à jour indépendantes.
5. **Plus tard, facultatif : relais de sortie.** Pour un établissement dont le pare-feu ne doit connaître qu'une adresse, un relais de sa région dépose **pour le compte** de la box sur les relais étrangers. Il ne fait que transmettre des requêtes déjà signées et des contenus déjà chiffrés (comme le routage privé de messages de SimpleX). Il ne stocke rien et ne déchiffre rien. Il masque en plus l'adresse IP de la box aux relais étrangers.
6. **Si une autorité de fédération apparaît** : reconnaissances signées par elle et acceptées par chaque responsable (option C, § 4), sans changer le format.

## 8. Questions ouvertes

1. **Cadre juridique** : convention entre établissements, responsables de traitement, hébergement des relais (HDS en France), transferts hors de l'Union européenne au sens du RGPD. La technique peut aider : les morceaux restent sur le relais de l'émetteur, donc dans sa juridiction, jusqu'au retrait. Elle ne tranche rien.
2. **Choix des relais par zone** : faut-il qu'une reconnaissance impose les relais admis (par exemple aucun relais hors UE), et que la box refuse un partenaire dont les relais sortent de cette liste ?
3. **Périmètre par défaut** : toute l'organisation, ou rien tant que des groupes ne sont pas désignés ? La proposition va vers *rien*, ce qui est plus sûr mais demande plus de gestes au responsable.
4. **Nombre d'accords** : à partir de combien d'organisations le N² de l'option B devient-il pénible, et une autorité de fédération (option C) se justifie-t-elle ?
5. **Mises à jour** : un éditeur unique pour toutes les organisations, ou un serveur de mises à jour par organisation ? La clé de l'éditeur devient une cible d'autant plus grande que le réseau grandit (double signature prévue dans la note d'architecture).
6. **Interopérabilité** : passerelle vers des infrastructures nationales qui exigent des profils IHE (XDS-I), hors du lien de box à box.
