# Phase 8 — Campagne de tests

> Objectif : dérouler les scénarios de la note d'architecture sur la chaîne complète, mesurer, et consigner ce qui marche et ce qui casse.
> Statut : **fait**. 6 scénarios réussis en réel (relais SimpleX locaux, Orthanc, box en conteneurs), **toutes les instances identiques à l'arrivée**. La campagne a révélé **4 défauts**, tous corrigés et vérifiés.
> **Rejouée sur IU-T** (novembre 2026, étape 5 de la [spécification](specification-protocole.md)) : mêmes 6 scénarios, tous réussis, résultats au moins équivalents et nettement meilleurs pour la coupure et l'examen de 2 Go ; voir § 7. SimpleX a ensuite été retiré : les § 1 à 6 décrivent la campagne d'origine, sur SimpleX.

## 1. Banc

Pile [Docker Compose](../deploy/docker-compose.yml) complète, sur un seul hôte (conteneur cloud : 4 vCPU, 15 Go de mémoire, Docker imbriqué) :

```
Orthanc A ──C-STORE──► box A + simplex-chat A ──► relais SMP + XFTP ◄── simplex-chat B + box B ──C-STORE──► Orthanc B
   site-a                                          réseau « relais »                                    site-b
```

- **Relais** : `smp-server` et `xftp-server` v7.0.1 (images officielles), sur un réseau à part ; aucun chemin direct entre les sites.
- **Clients** : `simplex-chat` v7.1.2, non modifié ; box ImagUnity de la branche courante.
- **Pilote** : [`deploy/campagne/campagne.py`](../deploy/campagne/campagne.py). Il génère des études CT synthétiques (patient `SYNTHETIQUE^CAMPAGNE`, pixels aléatoires, donc incompressibles), les importe dans Orthanc A, demande à Orthanc A de les envoyer à la destination `IU_TO_B`, suit le transfert par l'API de la box A et Orthanc B, puis **compare chaque instance d'Orthanc B à celle d'Orthanc A, jeu de données octet par octet** (hors préambule et méta-informations de fichier, réécrites par chaque PACS).
- **Perturbations**, appliquées dans l'espace réseau des conteneurs (image netshoot) : `iptables` (pare-feu, coupure), `tc` (débit), `nmap` (scan).
- **Fin d'étude** détectée après 5 s sans instance (30 s par défaut), pour que les mesures portent sur le transport.

Résultats bruts : [`deploy/campagne/resultats/`](../deploy/campagne/resultats/). Reproduire : § 6.

## 2. Résultats

Durées comptées depuis la demande d'envoi à Orthanc A. « Dépôt » : du paquet prêt à l'annonce (tous les paquets déposés sur le relais XFTP).

| Scénario | Étude | Dépôt | Restituée en B | Accusé en A | Vérification |
|---|---|---|---|---|---|
| Nominal | 100 inst., 50 Mo, 1 paquet | 1,5 s | 10,2 s | 10,2 s | 100/100 identiques |
| Pare-feu (tout entrant bloqué) | 100 inst., 50 Mo | 2,0 s | 10,7 s | 10,7 s | 100/100 |
| Liaison bridée à 20 Mbit/s | 200 inst., 100 Mo | 46,3 s | 100,6 s | 101,1 s | 200/200 |
| Coupure de 60 s pendant le dépôt | 600 inst., 300 Mo | 119,5 s | 143,4 s | 143,4 s | 600/600 |
| Box B éteinte 3 min 30 | 100 inst., 50 Mo | 1,5 s | 218,9 s (8,1 s après rallumage) | 243,8 s | 100/100 |
| Examen volumineux | 1 024 inst. 1024×1024, 2 Go, 4 paquets | 80,8 s | 154,4 s | 157,4 s | 1 024/1 024 |

Les débits mesurés ici (≈ 33 Mo/s au dépôt, ≈ 37 Mo/s au téléchargement, chiffrement et vérifications compris) sont ceux d'un hôte unique sans réseau réel : ce sont des **plafonds logiciels**, pas des débits Internet.

### 2.1 Nominal

Chronologie : C-STORE terminé à 0,5 s, étude empaquetée à 6,1 s (dont 5 s d'attente de fin d'étude), annoncée à 7,6 s, restituée et acquittée à 10,2 s. Moins de 5 s de transport pour 50 Mo.

### 2.2 Pare-feu et scan de ports

Scan `nmap` (TCP connect, ports 1-1024 et 5223, 5225, 8080, 11112) depuis le réseau des relais, c'est-à-dire « depuis Internet » :

| | Box A | Box B |
|---|---|---|
| Sans pare-feu | **8080** (console) et **11112** (DICOM) ouverts | idem |
| Pare-feu : connexions entrantes refusées côté relais | aucun port joignable | aucun port joignable |

Avec ce pare-feu en place sur les deux box, le transfert aboutit normalement (10,7 s, 100/100) : **la box n'a besoin d'aucune connexion entrante**. Toutes ses connexions vers les relais sont sortantes.

Constat : sans pare-feu, la box expose sa console et son entrée DICOM sur toutes ses interfaces. Dans un établissement, la box doit être derrière le pare-feu du site, sans port ouvert vers Internet, et ces deux ports réservés au réseau local (recommandation ci-dessous).

### 2.3 Liaison bridée

Débit limité à 20 Mbit/s en sortie de chaque box et de chaque relais (`tc tbf`). Dépôt de 100 Mo en 46 s, soit ≈ 17 Mbit/s utiles, et téléchargement en 48 s : l'efficacité est de l'ordre de 87 % du lien. Le noyau de ce banc n'a pas le module `netem` : **la latence et les pertes n'ont pas pu être simulées** (§ 5).

### 2.4 Coupure réseau

Tout le trafic de la box A côté relais coupé 60 s, 3 s après le début du dépôt d'un paquet de 300 Mo. Le dépôt reprend seul, sans erreur ni action : annonce 55 s après le rétablissement (temporisation de nouvelle tentative de simplex-chat, puis fin du dépôt), accusé 16 s plus tard.

**Observation hors protocole.** Lors d'une série interrompue par une erreur du pilote, la box A est restée coupée **environ 8 minutes** pendant le dépôt de trois transferts (50 Mo, 300 Mo, 2 Go). Au rétablissement, les trois ont repris seuls : 50 et 300 Mo acquittés 30 s après, 2 Go déposé puis restitué (1 024/1 024) 2 min 20 s après.

### 2.5 Destinataire éteint et inspection des relais

La box B (et son client SimpleX) est arrêtée avant l'envoi. La box A dépose et annonce sans attendre (1,5 s) : **l'émetteur ne dépend pas de la présence du destinataire**. Pendant l'attente, le relais SMP est arrêté (il écrit alors ses messages en attente sur disque) et tout ce que les deux relais ont écrit est inspecté :

| Recherche dans 1 450 fichiers, 5,5 Go (morceaux XFTP, journaux et files SMP) | Résultat |
|---|---|
| Nom du patient, identifiant du patient, Study Instance UID | **absents** |
| Texte des messages ImagUnity (`imagunity.transfer`) | **absent** |
| Entropie des 1 345 morceaux XFTP | 8,0 bits/octet en moyenne, 7,9998 au minimum : indiscernable du hasard |
| Séquence `DICM` | 1 occurrence, au milieu d'un morceau et sans préambule DICOM ; attendue par hasard ≈ 1,3 fois dans 5,5 Go aléatoires |

Box B rallumée après 3 min 30 : étude restituée **8 s** après. L'accusé suit 25 s plus tard. Le client de A se reconnectait au relais SMP redémarré pendant l'inspection, ce qui explique probablement ce délai.

Constat : les morceaux chiffrés restent sur le relais XFTP après téléchargement, jusqu'à leur expiration (48 h). C'étaient 5,5 Go au moment de l'inspection, toutes les études de la campagne.

### 2.6 Examen volumineux

2 Go en 4 paquets de 900 Mo au plus. Empaquetage en 6,6 s, dépôt en 81 s, téléchargement, vérification et restitution en 56 s. Mémoire et disque sans incident (le paquet est lu et écrit en flux).

## 3. Défauts révélés et corrigés

| # | Défaut | Effet | Correction |
|---|---|---|---|
| 1 | Images des relais : nom court refusé (`ADDR`), certificat HTTPS exigé avec un nom de domaine, variable `PASS` obligatoire pour XFTP | les relais ne démarraient pas : le Compose n'avait jamais pu être essayé, faute de Docker sur le poste | adresses IPv4 fixes sur le réseau des relais, `WEB_MANUAL`, `PASS` vide ([Compose](../deploy/docker-compose.yml)) |
| 2 | Box et client SimpleX dans deux conteneurs sans disque commun | `fileNotFound` : le client ne voyait pas les paquets à envoyer | volume `/data` partagé entre `box-x` et `chat-x` |
| 3 | Le client SimpleX déchiffre dans son `/tmp`, puis renomme vers `/data/inbox` | fichier reçu à 100 % mais jamais livré : un renommage ne traverse pas deux systèmes de fichiers | `--temp-folder /data/tmp` ([entrypoint](../deploy/simplex-chat/entrypoint.sh)) ; à respecter dans tout déploiement |
| 4 | Ce que le client SimpleX reçoit avant que la box ne s'y connecte n'est signalé à personne | box redémarrée pendant un transfert : fichier jamais accepté, transfert bloqué indéfiniment | **rattrapage au démarrage** (`TransportPort.catchUp`) : relecture des 200 derniers éléments de chaque conversation ; l'annonce porte sa date d'émission, pour qu'une annonce relue ne rouvre pas un transfert en échec. Vérifié en réel : le transfert bloqué restitué 12 s après le redémarrage |

Les défauts 2 et 3 n'apparaissaient pas lors de l'essai réel de la [phase 5](phase5-reception.md), où box et client tournaient sur le même disque du poste. Le défaut 4 touche tout déploiement : il suffit d'une mise à jour ou d'un redémarrage de la box au mauvais moment.

Propre à ce banc, sans conséquence en déploiement : le noyau de l'hôte n'a **pas d'IPv6** et les relais SimpleX écoutent par défaut sur `::`. Un petit module préchargé (`compose.sans-ipv6.yml`, retiré avec SimpleX) restreignait leur écoute à IPv4 ; le relais IU-T n'en a pas besoin.

## 4. Recommandations pour le pilote

1. **Exposition réseau** : faire écouter la console (8080) et l'entrée DICOM (11112) sur l'interface du réseau local seulement (`server.address`, adresse d'écoute DICOM), en plus du pare-feu du site. **Fait** : `SERVER_ADDRESS` et `IMAGUNITY_DICOM_BIND_ADDRESS` ; la box journalise un avertissement au démarrage si l'une des deux écoute sur toutes les interfaces. Dans le Compose, chaque box écoute sur son adresse du réseau du site (172.31.x.10). Vérifié : depuis le réseau des relais, aucun port ouvert, même sans pare-feu ; la console et l'entrée DICOM restent joignables du site.
2. **Ménage sur le relais** : supprimer les fichiers XFTP dès l'accusé reçu, au lieu d'attendre leur expiration à 48 h. **Fait** : la box A retire des relais les paquets d'un transfert dès l'accusé (réussi ou en échec) du destinataire (`/fcancel` de simplex-chat). Vérifié en réel : aucun morceau ne reste sur le relais après un transfert de 50 Mo.
3. **Déploiement** : client SimpleX et box sur le même système de fichiers pour `/data` (dossier temporaire compris).
4. **Rattrapage** : il couvre le redémarrage de la box. Une reconnexion automatique au client SimpleX, si celui-ci redémarre seul pendant que la box tourne, reste à faire. **Fait** : la box se reconnecte d'elle-même (attente de 1 s, doublée à chaque échec jusqu'à 30 s), puis relance le rattrapage. Dans le Compose, la box et son client partagent la pile réseau d'un petit conteneur `net-x` (comme un pod), pour que chacun puisse redémarrer seul. Vérifié en réel : client de B redémarré, box reconnectée en 1 s, transfert suivant réussi (100/100).

## 5. Non couvert, à mesurer ailleurs

| Point | Pourquoi | Comment |
|---|---|---|
| Latence et pertes de paquets | module `netem` absent du noyau de ce banc | même pilote avec `NETEM=1` (40 ± 10 ms par sens, 1 % de pertes, 20 Mbit/s) sur un hôte Linux standard |
| Réseau réel | banc sur un seul hôte | box sur deux sites (ou deux machines), relais publics ou hébergés, mêmes scénarios |
| Examens de plus de 2 Go | disque du banc (≈ 7 copies de l'étude au plus fort) | `scenario_volumineux(go=10)` sur un hôte plus grand |
| Transferts simultanés, redémarrage du relais XFTP en cours de dépôt | hors du périmètre de cette campagne | à ajouter au pilote |

## 6. Reproduire

```bash
# 1. Construire la box
cd backend && ./mvnw -DskipTests package && cd ..
# 2. Préparer et démarrer la pile (annuaire et box enrôlées : deploy/README.md)
cd deploy
./campagne/prepare.sh
docker compose -f docker-compose.yml -f campagne/compose.campagne.yml up -d --build
# 3. Lancer la campagne (Python 3.12+)
python3 -m venv campagne/.venv && campagne/.venv/bin/pip install -r campagne/requirements.txt
campagne/.venv/bin/python campagne/campagne.py tout          # ou : nominal pare-feu liaison-degradee ...
```

Le pilote appaire les box si besoin, nettoie les Orthanc après chaque scénario et écrit ses mesures dans `campagne/resultats/`. Il a besoin de Docker (images `nicolaka/netshoot` pour `tc`, `iptables` et `nmap`).

## 7. Campagne rejouée sur IU-T

Même banc et même pilote, avec le transport IU-T à la place de SimpleX : relais IU-T (PostgreSQL et disque) et annuaire de l'organisation sur le réseau « relais », box en version signée 0.7.0, TPM logiciel, mises à jour signées, **aucun conteneur SimpleX**. Box enrôlées, appairées par l'annuaire. Résultats bruts : [`campagne-20261112-191908.json`](../deploy/campagne/resultats/campagne-20261112-191908.json).

```
Orthanc A ──C-STORE──► box A ──► relais IU-T (+ annuaire) ◄── box B ──C-STORE──► Orthanc B
   site-a                         réseau « relais »                   site-b
```

| Scénario | Étude | Dépôt IU-T (SimpleX) | Accusé en A, IU-T (SimpleX) | Vérification |
|---|---|---|---|---|
| Nominal | 100 inst., 50 Mo | 1,9 s (1,5 s) | **12,7 s** (10,2 s) | 100/100 identiques |
| Pare-feu (tout entrant bloqué) | 100 inst., 50 Mo | 1,2 s (2,0 s) | **10,3 s** (10,7 s) | 100/100 |
| Liaison bridée à 20 Mbit/s | 200 inst., 100 Mo | 48,1 s (46,3 s) | **103,9 s** (101,1 s) | 200/200 |
| Coupure de 60 s pendant le dépôt | 600 inst., 300 Mo | 73,7 s (119,5 s) | **95,9 s** (143,4 s) | 600/600 |
| Box B éteinte | 100 inst., 50 Mo | 1,2 s (1,5 s) | **108,3 s**, restituée **11,1 s après le rallumage** (8,1 s) | 100/100 |
| Examen volumineux | 1 024 inst. 1024×1024, 2 Go, 4 paquets | 23,3 s (80,8 s) | **92,0 s** (157,4 s) | 1 024/1 024 |

- **Pare-feu et scan de ports** : depuis le réseau « relais », aucun port ouvert sur les box, avant comme après le pare-feu (console et entrée DICOM n'écoutent que sur le réseau du site) ; le transfert passe avec toute connexion entrante bloquée : la box ne fait que des connexions sortantes.
- **Coupure** : l'annonce part **9,6 s** après le rétablissement ; l'envoi reprend aux morceaux non téléversés (état enregistré à chaque morceau), au lieu de reprendre le fichier XFTP.
- **Destinataire éteint (90 s ici, 3 min 30 avec SimpleX)** : pendant l'attente, les **1 329 fichiers** du relais (121 Mo : morceaux sur disque et fichiers de la base PostgreSQL, écrits sur disque avant l'inspection) ne contiennent **ni le nom ni l'identifiant du patient, ni le Study Instance UID, ni la séquence `DICM`, ni le texte des messages** ImagUnity. Entropie des 13 morceaux : 7,77 bits/octet en moyenne ; le minimum (5,05) est celui du dernier morceau, complété par des zéros jusqu'à la taille standard (§ 5 de la spécification).
- **Examen de 2 Go** : 41 % plus rapide de bout en bout, dépôt 3,5 fois plus rapide (téléversement direct au relais, sans client intermédiaire ni second chiffrement des morceaux).
- **Nominal** : 2,5 s de plus que SimpleX, dans le bruit d'une mesure unique sur ce banc ; la phase RECEIVED → ANNOUNCED dure 2,5 s (empaquetage, chiffrement, dépôt, envoi de l'annonce par la boîte d'envoi).
- Après les accusés, le relais est vide (morceaux supprimés à l'accusé, messages acquittés).

Constat : le **remplissage par des zéros** du dernier morceau laisse voir au relais la taille exacte du paquet (à quelques octets près). Le relais connaît déjà la taille des morceaux, et le paquet est chiffré ; c'est une métadonnée de taille, à traiter avec la taille de morceau adaptative (étape 6) : remplissage aléatoire, ou chiffrement du remplissage.

**Étape 6 (annonce anticipée, remplissage aléatoire)**, mêmes scénarios, box 0.8.0 ([résultats bruts](../deploy/campagne/resultats/campagne-20261112-201012.json)) : liaison bridée **67,7 s** (103,9 s à l'étape 5, −35 %), examen de 2 Go **79,8 s** (92,0 s, −13 %), autres scénarios inchangés ; entropie minimale des morceaux sur le relais **8,0** bits/octet (5,05 avec des zéros). Toutes les instances identiques.

**Taille de morceau adaptative** (box 0.9.0), scénarios `liaison-lente` et `coupure-liaison-lente` (2 Mbit/s, 20 Mo) : liaison lente **105,6 s** avec morceaux adaptatifs contre 134,7 s avec morceaux fixes de 4 Mio (restitution 1,8 s après la fin du téléversement, contre 19,0 s) ; coupure de 30 s **166,3 s** contre 221,5 s. 40/40 instances identiques. Détails : [spécification § 13.4](specification-protocole.md#134-avancement).

