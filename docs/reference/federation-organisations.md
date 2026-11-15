# Fédération entre organisations

> Objectif : permettre à des box d'**organisations différentes** (régions, pays) d'échanger des examens, sans réseau unique ni autorité centrale imposée, et sans affaiblir les garanties actuelles (relais aveugles, chiffrement de bout en bout, aucun port entrant).
>
> Statut : **étape 1 réalisée** (novembre 2026), essayée sur le banc à deux organisations ([`deploy/banc-essai/federation`](../../deploy/banc-essai/federation/README.md)). Étapes 2 à 6 à venir (§ 7).
>
> Décisions prises :
> - reconnaissance mutuelle signée entre organisations (§ 4) ;
> - pas de liaison entre relais (§ 3) ;
> - périmètre vide par défaut, un identifiant de box n'appartient qu'à une organisation, relais du partenaire listés dans l'accord (§ 5).
>
> Restent ouvertes les questions du § 11. La première version de cette note a été relue à la lumière du code : les verrous qu'elle n'avait pas vus sont au § 2.2. Le mode d'emploi pour le responsable est au [guide du responsable, § 2.9](../guides/guide-responsable.md).

## 1. Point de départ

Aujourd'hui, **l'organisation est l'unique cercle de confiance**. Le code le confirme :

| Composant | Ce qui le rend mono-organisation |
|---|---|
| Box | Une clé de responsable et un annuaire : `DirectoryProperties` (une `url`, une `organizationKey`, un `organizationCode`). Un seul `LocalDirectory` et un seul `EntryVerifier`. |
| Vérification des entrées | `EntryVerifier` lève « Entrée d'une autre organisation » : une entrée étrangère est une erreur de sécurité, pas un état. Le même vérificateur sert à la box, au relais et au service d'annuaire. |
| Relais | Une clé épinglée (`IMAGUNITY_RELAY_ORGANIZATION_KEY`) et un annuaire lu toutes les 5 minutes. La [spécification § 6.4](specification-protocole.md) prévoit qu'il serve « chaque organisation », mais le code ne le permet pas. |
| Caches d'annuaire | Box et relais : tables `directory_entry` et `directory_serial` indexées par `box_id` **seul**, sans organisation. |
| Partenaires et autorisations | Table `peer` (nom local, `box_id`). L'envoi exige un partenaire lié dont l'entrée est active **dans l'annuaire local** (`DirectoryPeerAuthorization`). La réception exige que l'émetteur soit dans l'annuaire local (`MessageSigner`, `IuTransport`). |

Une organisation régionale suffit donc pour un réseau sous une seule autorité. Au-delà, deux box de deux organisations ne peuvent pas se parler.

## 2. Ce qui marche déjà, ce qui bloque

### 2.1 Les relais n'ont pas à se parler

Le protocole ne suppose **pas** que l'émetteur et le destinataire utilisent le même relais :

| Étape | Où | Qui s'y connecte, et comment |
|---|---|---|
| Morceaux du paquet (IU-FILE) | relais **de l'émetteur** | l'émetteur dépose (requête signée, identité vérifiée par le relais) ; le destinataire retire **par capacité**, sans contrôle d'identité |
| Annonce et accusé (IU-MSG) | file **du destinataire**, sur **son** relais : l'adresse complète, relais compris, figure dans son entrée d'annuaire | l'émetteur dépose (requête signée, identité vérifiée) ; le destinataire relève par capacité |

Une box de la région A qui écrit à une box de la région B se connecte donc directement au relais de B pour déposer l'annonce. La box de B télécharge les morceaux sur le relais de A. Tout reste en connexions sortantes.

### 2.2 Les quatre verrous

1. **Confiance dans les entrées** : la box de B rejette toute entrée signée par le responsable de A (`EntryVerifier`).
2. **Dépôt sur un relais étranger** : le relais de B authentifie chaque dépôt d'après **son** annuaire. Une box de A n'y figure pas, donc son annonce est refusée (401).
3. **Morceaux hors des relais connus** : à la réception, `IuTransport.knownReplicas` écarte toute copie hébergée sur un relais que la box n'a pas configuré (« morceau sur un relais hors de l'organisation »). Les morceaux de A, posés sur le relais de A, seraient donc refusés par B.
4. **Identifiants de box** : tout est indexé par `box_id` seul (caches, messages signés, appairage, autorisations). Si l'on accepte des entrées étrangères sans précaution, une organisation partenaire peut publier une entrée portant l'identifiant d'une de nos box, ou de celle d'un autre partenaire, et **s'y substituer**. C'est le principal risque nouveau de la fédération (§ 5.2).

Aucun de ces verrous ne demande de relier les relais entre eux.

## 3. Relier les relais entre eux : non

L'idée serait de faire suivre les données de relais en relais, à la manière du courrier électronique : chaque box ne parlerait qu'au relais de sa région, qui transmettrait au relais de la région destinataire. On l'écarte comme mécanisme de base :

- le relais deviendrait un **routeur avec état** (files de transit, nouvelles tentatives) au lieu d'un stockage aveugle ;
- chaque relais traversé verrait volumes et horaires, et les morceaux seraient copiés de relais en relais (double stockage, double effacement à garantir) ;
- chaque paire d'exploitants devrait s'authentifier, et chacun dépendrait de la disponibilité des autres ;
- la responsabilité juridique de la donnée en transit deviendrait floue ;
- il faudrait concevoir, sécuriser et auditer un protocole relais-relais.

Son seul vrai atout : une seule adresse sortante à autoriser dans le pare-feu de l'établissement. Ce besoin se traite plus tard, sans toucher au modèle, par un **relais de sortie** facultatif (§ 9).

## 4. Modèle de confiance : reconnaissance mutuelle signée

| Option | Principe | Verdict |
|---|---|---|
| A. Box multi-organisations | La box épingle plusieurs clés et s'enrôle dans chaque organisation | Écartée : un CHU qui échange avec 30 régions s'enrôle 30 fois |
| **B. Reconnaissance mutuelle signée** | Le responsable de A signe un document qui **reconnaît** l'organisation B (clé, relais, périmètre) ; B fait de même pour A | **Retenue** : chaque box n'épingle que **sa** clé de responsable, et les clés étrangères lui arrivent signées par son propre responsable. Accord bilatéral, révocable, borné ; chaque organisation reste souveraine |
| C. Racine de fédération | Une autorité signe les clés des responsables | Plus tard peut-être : point critique unique, gouvernance lourde |

Le format reste le même si une autorité de fédération apparaît un jour : seule changerait la clé qui signe la reconnaissance, que le responsable accepterait (§ 9).

## 5. Décisions

### 5.1 Périmètre : rien par défaut

Une reconnaissance sans groupes ne permet **aucun** échange. Le responsable désigne :

- les **groupes locaux** autorisés à échanger avec le partenaire ;
- les **groupes du partenaire** acceptés.

Il le fait explicitement, avec `*` pour « toute l'organisation ». Les groupes sont ceux, déjà signés, des entrées d'annuaire et de la [configuration centralisée](configuration-centralisee.md).

### 5.2 Un identifiant de box n'appartient qu'à une organisation

Plutôt que d'ajouter l'organisation dans toutes les tables, tous les messages et toutes les autorisations, on garantit qu'un identifiant de box ne désigne jamais deux box :

- une entrée de **l'organisation locale** l'emporte toujours ;
- pour les partenaires, la **première organisation vue** garde l'identifiant. Ce propriétaire est enregistré dans le cache, pour qu'un redémarrage ne change rien ;
- une entrée qui revendique un identifiant déjà tenu par une autre organisation est **rejetée** et signalée dans la console (« Identifiant de box revendiqué par deux organisations »). Si c'est l'organisation locale qui le publie, l'entrée étrangère est retirée du cache.

Les identifiants sont tirés au hasard (UUID, 122 bits) : une collision ne peut être que malveillante ou une erreur grave. La règle s'applique à l'identique sur la box et sur le relais.

### 5.3 Relais du partenaire listés dans l'accord

La reconnaissance énumère les **relais autorisés** du partenaire. Une entrée du partenaire qui publie une adresse sur un autre relais est rejetée. Une box n'accepte des morceaux que sur ses propres relais ou sur ceux d'un partenaire reconnu pour l'organisation de l'émetteur. Le responsable maîtrise ainsi où vont les données de ses établissements, et dans quelle juridiction : aucun relais hors UE, par exemple.

## 6. Conception de l'étape 1

### 6.1 Document de reconnaissance

Il est signé par la clé du responsable (Ed25519 + ML-DSA-65, signature des octets exacts, comme les entrées) et publié sur l'annuaire **de l'organisation qui reconnaît** :

```json
{
  "suite": "IU1",
  "type": "recognition",
  "organization": "grand-est",
  "partner": {
    "organization": "bourgogne-fc",
    "key": { "ed25519": "<X.509 base64>", "mlDsa65": "<X.509 base64>" },
    "directory": "https://annuaire.bourgogne-fc.example",
    "relays": ["https://relais.bourgogne-fc.example"]
  },
  "scope": { "localGroups": ["imagerie-nord"], "partnerGroups": ["*"] },
  "serial": 1,
  "validFrom": "2026-12-01T00:00:00Z",
  "validUntil": "2027-03-01T00:00:00Z",
  "revoked": false
}
```

- **Empreinte vérifiée hors bande** entre les deux responsables, une fois par accord et non une fois par box. L'outil l'affiche et la fait confirmer.
- Le partenaire est identifié par **l'empreinte de sa clé**. Son identifiant (`organization`, celui que portent ses entrées) n'est qu'un libellé : deux organisations peuvent toutes deux s'appeler `chu-exemple`.
- `directory` : l'annuaire du partenaire, que seul le service d'annuaire local contacte (§ 6.2).
- **Numéro, validité, révocation** : mêmes règles que les entrées (pas de retour en arrière, réémission pour renouveler ou révoquer). La validité est courte, 90 jours au plus, tant que la preuve de fraîcheur (§ 7) n'existe pas.

### 6.2 Où les box et les relais trouvent les entrées des partenaires

Le **service d'annuaire de l'organisation relaie les entrées de ses partenaires**. Il les lit sur l'annuaire du partenaire et les sert telles quelles (`GET /directory/v1/partners/{empreinte}/entries`). Les entrées étant signées, ce relais n'a pas à être de confiance : box et relais les vérifient avec la clé du partenaire tirée de la reconnaissance.

- La box et le relais n'ont **qu'une adresse d'annuaire**, comme aujourd'hui. Pas d'ouverture de pare-feu vers les annuaires étrangers.
- L'annuaire étranger n'est contacté que par le service d'annuaire, depuis le site central.
- La box n'**utilise** que les entrées du **périmètre** (groupes du partenaire acceptés) ; les autres restent en cache, pour l'affichage et au cas où le périmètre s'élargirait.

Un annuaire qui retiendrait des entrées ou des révocations reste le risque connu (constat C2). L'étape 2 le traite pour tous les annuaires à la fois (§ 7).

### 6.3 Annuaire fédéré (module `directory`, partagé par la box et le relais)

C'est l'annuaire local plus un cache par partenaire reconnu. Une entrée étrangère, signée par l'organisation P, n'est retenue que si :

1. une reconnaissance de P, **active** et signée par le responsable local, existe ;
2. l'entrée est signée par la clé de P figurant dans cette reconnaissance, et son organisation est celle de P ;
3. ses groupes rencontrent les groupes du partenaire acceptés ;
4. ses adresses sont toutes sur des relais listés par la reconnaissance ;
5. son identifiant n'est pas tenu par une autre organisation (§ 5.2) ;
6. les règles actuelles s'appliquent : validité, révocation, pas de retour en arrière.

Une reconnaissance expirée ou révoquée retire aussitôt les entrées de ce partenaire des box utilisables. Le cache est gardé, pour l'affichage.

### 6.4 Box

| Moment | Règle |
|---|---|
| Appairage | La console propose les box des partenaires dans le périmètre, rangées par organisation. Une box locale hors des groupes locaux de l'accord ne peut pas s'appairer avec ce partenaire. |
| Envoi | En plus des contrôles actuels : accord actif, box locale dans les groupes locaux, box destinataire dans les groupes du partenaire. Sinon, le transfert échoue avec le motif (« Accord avec bourgogne-fc expiré »). |
| Réception | Mêmes contrôles sur l'émetteur ; un message d'une box hors périmètre est écarté et journalisé. |
| Morceaux | Relais admis = relais de la box + relais listés par la reconnaissance de l'organisation de l'émetteur. |
| Console | Organisation de chaque box, organisations partenaires (état de l'accord, périmètre, relais, dernière lecture), motif quand une box du partenaire ne peut pas échanger avec celle-ci, collisions d'identifiant. Un accord **à sens unique** se voit au refus des dépôts par le relais du partenaire : la box garde le message et l'écrit dans son journal, une fois par heure. |

La réciprocité n'est pas un champ du document : un échange ne marche que si les deux responsables ont signé, puisque chaque côté applique ses propres règles.

### 6.5 Relais

- Le relais lit son annuaire fédéré comme une box : les entrées de son organisation, et celles des partenaires dans le périmètre.
- Une box d'un partenaire reconnu peut **déposer des messages**, et seulement cela, dans la file d'une box locale dont les groupes sont dans le périmètre de l'accord. Elle ne crée jamais de file ni de morceau sur un relais étranger : ses morceaux restent sur les relais de sa propre organisation.
- Quotas existants (2 000 messages en attente par émetteur et par file, 10 000 par file) appliqués tels quels.
- Le retrait des morceaux reste par capacité : **rien ne change pour le relais de l'émetteur**.

### 6.6 Outil du responsable et interface locale

- Commandes :
  - `reconnaitre --cles --partenaire <fichier .pub.json> --relais <url,…> --groupes-locaux <…> --groupes-partenaire <…> [--validite-jours 90]` : affiche l'empreinte du partenaire et demande confirmation, puis signe et publie ;
  - `reconnaissances` : liste ;
  - renouveler et révoquer une reconnaissance.
- Interface locale : onglet « Organisations partenaires » (liste, état, renouvellement, révocation), sur le modèle de l'onglet « Box ».
- Le service d'annuaire publie et sert les reconnaissances (`POST` réservé au responsable, `GET` pour les box et le relais) et relaie les entrées des partenaires.

### 6.7 Compatibilité : lire sans refuser les champs inconnus

Les entrées sont aujourd'hui lues avec **refus des champs inconnus**. Ajouter un champ à une entrée (versions de protocole, par exemple) rendrait donc ses entrées illisibles pour toutes les box pas encore mises à jour. Or entre organisations, les mises à jour ne sont plus simultanées.

L'étape 1 change la lecture des documents signés (entrées, reconnaissances, configurations) : un champ inconnu est **ignoré**, et non plus refusé. La signature couvrant les octets exacts, rien n'est perdu pour l'intégrité. Aucun champ nouveau n'est écrit dans les entrées avant que toutes les box le comprennent.

### 6.8 Banc d'essai et critères de réussite

Deux organisations, chacune avec son service d'annuaire, son relais et une box, données synthétiques uniquement :

- un examen va d'une box de A vers une box de B, arrive intact, et l'accusé revient ; puis l'inverse ;
- refusés, chacun avec un motif visible dans la console :
  - accord révoqué ;
  - accord à sens unique ;
  - box hors périmètre ;
  - entrée du partenaire sur un relais non listé ;
  - identifiant de box revendiqué par le partenaire ;
- le relais de B refuse le dépôt d'une box de A hors périmètre ; le relais de A ne voit jamais la box de B autrement que par des retraits par capacité.

## 7. Étapes suivantes

| Étape | Contenu | Pourquoi |
|---|---|---|
| **2. Fraîcheur** — réalisée | Une clé de fraîcheur en ligne, déléguée par le responsable (30 jours), signe un état daté de l'annuaire : le plus haut numéro de chaque entrée et de chaque reconnaissance. Une box ou un relais tient pour inutilisable une box dont l'entrée reçue est plus ancienne que l'annoncée, et arrête les échanges avec une organisation dont la preuve a plus de 48 h ([annuaire signé, § 5 ter](annuaire-signe.md)) | La preuve d'un partenaire est signée chez lui : notre annuaire, qui la relaie, ne peut ni la fabriquer ni masquer ses révocations. Ferme le constat C2 du pré-audit, avec une limite : une compromission du service d'annuaire lui-même, où la clé est en ligne |
| **3. Limites de débit** — réalisée | Requêtes par minute et par box déposante sur le relais (600 pour une box de l'organisation, 120 pour une box partenaire), et refus avant vérification d'une adresse qui multiplie les requêtes mal signées | Constat R4 fermé |
| **4. Annuaires** — réalisée | Synchronisation par différence (`GET /entries?since=<curseur>`), lecture réservée aux box enrôlées des organisations reconnues (requêtes signées) | Aujourd'hui, chaque box lit toutes les entrées (environ 7,5 Ko chacune) toutes les 5 minutes, et l'annuaire est public : 10 000 box feraient 75 Mo par lecture, et l'annuaire fédéré deviendrait un annuaire public des établissements. Réalisée avec lecture complète au moins une fois par jour et dès qu'une preuve de fraîcheur signale un manque ; lecture réservée sur option, les partenaires signant avec leur clé de fraîcheur ([annuaire signé, § 5 quater](annuaire-signe.md)) |
| **5. Versions** | Versions du protocole annoncées dans les entrées (`"protocols": ["iu1.0", "iu1.1"]`), choix de la plus haute version commune, refus propre sinon | Nécessaire dès que les organisations mettent à jour à des rythmes différents. Rendue possible par le § 6.7 |
| **6. Films explicatifs** | Courtes vidéos animées et sous-titrées : vue d'ensemble, organisation, enrôlement d'une box, voyage d'un examen, incidents, et échange entre organisations ([plan](../films/plan.md)) | Faire comprendre le fonctionnement de bout en bout sans lire la spécification. Le film sur la fédération suit l'étape 1 |

## 8. Ce que ça change dans le code (étape 1)

| Module | Changement |
|---|---|
| `directory` | Reconnaissance (format, signature, vérification) ; annuaire fédéré et règle des identifiants ; lecture tolérant les champs inconnus ; commandes et onglet de l'interface locale |
| `directory-server` | Publication et lecture des reconnaissances ; relais des entrées des partenaires (migration de base) |
| `app`, `transport` | Annuaire fédéré à la place de l'annuaire local ; relais admis pour les morceaux ; contrôles de périmètre à l'appairage, à l'envoi et à la réception |
| `persistence` (box) et `relay` | Organisation propriétaire de chaque identifiant dans les caches (migrations) |
| `relay` | Annuaire fédéré ; dépôt de messages ouvert aux box reconnues dans le périmètre |
| `api`, `frontend` | Organisation et état de l'accord par partenaire, accords à sens unique, collisions |
| `docs` | Guide du responsable (accords), guide d'installation, spécification (§ 6.4, et § 8.4 qui ne décrit plus les vrais types de messages IU-T : `iu.contact`, `iu.text`, `iu.file`) |

Découpage en lots, chacun fusionné avec la CI verte :

1. module `directory` et service d'annuaire ;
2. box ;
3. relais ;
4. interface locale, banc d'essai à deux organisations, documentation.

## 9. Plus tard, facultatif

- **Relais de sortie** : pour un établissement dont le pare-feu ne doit connaître qu'une adresse, un relais de sa région dépose **pour le compte** de la box sur les relais étrangers. Il ne transmet que des requêtes déjà signées et des contenus déjà chiffrés, ne stocke rien et ne déchiffre rien. Il masque en plus l'adresse IP de la box aux relais étrangers.
- **Autorité de fédération** : reconnaissances signées par elle et acceptées par chaque responsable (option C), sans changer le format.

## 10. Ce que la fédération ne change pas

- Le contenu des examens reste chiffré de bout en bout, de box à box. Aucun relais ni service d'annuaire, local ou étranger, ne peut le lire.
- La clé du responsable reste hors ligne, sur son poste.
- Les morceaux restent sur le relais de l'émetteur, donc dans sa juridiction, jusqu'au retrait.

## 11. Questions ouvertes

1. **Cadre juridique** : convention entre établissements, responsables de traitement, hébergement des relais (HDS en France), transferts hors de l'Union européenne au sens du RGPD. La liste des relais autorisés (§ 5.3) aide, mais ne tranche rien.
2. **Nombre d'accords** : à partir de combien d'organisations les accords deux à deux (N²) deviennent-ils pénibles, et une autorité de fédération se justifie-t-elle ?
3. **Mises à jour** : un éditeur unique pour toutes les organisations, ou un serveur de mises à jour par organisation ? La clé de l'éditeur devient une cible d'autant plus grande que le réseau grandit.
4. **Interopérabilité** : passerelle vers des infrastructures nationales qui exigent des profils IHE (XDS-I), hors du lien de box à box.
