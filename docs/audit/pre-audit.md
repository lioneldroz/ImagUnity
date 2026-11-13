# Pré-audit interne

> Revue faite avant l'[audit externe](dossier-audit.md), en novembre 2026, sur le code de la branche principale.
> Elle ne remplace pas l'audit. Les constats restants sont signalés à l'auditeur, avec ce qui a déjà été vérifié.

## 1. Méthode

Cinq revues indépendantes, une par zone, en lecture seule du code et de la documentation :

| Zone | Préfixe | Modules |
|---|---|---|
| Cryptographie et documents signés | C | `protocol`, `directory`, `packaging`, `release`, `app/config` (TPM) |
| Relais | R | `relay`, `deploy/relais-reparti`, `deploy/k3s` |
| Données reçues par la box | B | `transport`, `orchestration` |
| Console, API et entrée DICOM | L | `api`, `app`, `dicom`, console |
| Service d'annuaire, mises à jour, déploiement | D | `directory-server`, `release`, `deploy` |

Chaque constat a été relu avant correction. Les plus graves ont été reproduits par un test qui échouait avant la correction : B1, B2, L2, L3, R1, R3. Chaque correction est livrée avec ses tests. La couverture minimale imposée est conservée : 95 % des lignes et 75 % des branches par module.

Gravités : celles des revues, sans score CVSS (le score est attendu de l'auditeur).

## 2. Synthèse

| Statut | Nombre |
|---|---|
| Corrigé | 33 |
| Atténué ou corrigé en partie (le reste est décrit) | 9 |
| Accepté ou reporté, documenté | 15 |

Changements de comportement à connaître :

- **Messages signés** : ils nomment maintenant la box destinataire. Les box à jour refusent les messages qui n'ont pas ce champ, donc **toutes les box d'une organisation se mettent à jour ensemble**.
- **Relais** :
  - une box inconnue reçoit `401` (avant : `403`) ;
  - au-delà de 2 000 messages en attente d'une même box dans une file : `429`.
- **Outil du responsable** :
  - `configuration-signer` demande une confirmation (`--confirmer` pour un script) ;
  - la validité des entrées est maintenant de 90 jours par défaut (avant : 365).
- **Pile Compose d'essai** : les ports sont publiés sur `127.0.0.1` seulement.
- **K3s** :
  - l'entrée est en HTTPS seulement, et seul `/iu/` est routé ;
  - la base et le stockage S3 d'essai sont dans `deploy/k3s/essai`.
- **Migrations de base** :
  - relais : `V4__empreinte_des_entrees` ;
  - box : `V7__empreinte_des_entrees`.

## 3. Constats

### 3.1 Données reçues par la box (B)

| N° | Gravité | Constat | Statut | Suite donnée |
|---|---|---|---|---|
| B1 | Élevée | La poignée de main n'est pas liée à l'identité de transport : une box peut se faire enregistrer sous le nom d'une autre | Corrigé | Une preuve d'identité est écartée si elle vient d'un autre pair que la box authentifiée par le transport (`PeerHandshake`, `TransportPort.authenticatedBox`) |
| B2 | Élevée | Un appairage en attente reste ouvert, et le nom choisi peut revenir à une autre box | Corrigé | L'appairage attend la box désignée dans l'annuaire, et une autre box ne le prend ni ne l'annule (`PairingService.join(name, address, expectedBox)`) |
| B3 | Élevée | Une box peut bloquer la file de réception d'une autre avec 20 messages | Corrigé | Un message d'un émetteur inconnu est gardé, la relève continue au-delà, et il est acquitté sans traitement au bout d'une heure (`IuMailbox`). Quota par émetteur sur le relais (R4) |
| B4 | Élevée | Attente sans fin en annonce anticipée (ou sur 425), qui bloque tous les transferts | Corrigé | Délai d'abandon réel, au plus tard à l'expiration de la description (`IuFileDownloader`). Téléchargements sur un sémaphore distinct des envois |
| B5 | Moyenne | Les messages signés ne nomment pas leur destinataire : une box compromise peut les faire suivre | Corrigé | Champ signé `to` (box destinataire), vérifié à l'ouverture (`SignedMessage`). Vecteurs de test régénérés. Voir aussi C3 |
| B6 | Moyenne | Fragments de description sans borne | Corrigé | Identifiant de transfert de 22 caractères, 64 Kio par fragment, au plus 64 descriptions incomplètes par pair, oubliées après une heure. La description doit porter l'identifiant de ses fragments |
| B7 | Moyenne | Fichiers proposés téléchargés sans annonce et jamais nettoyés | En partie | Le fichier partiel est supprimé quand la réception échoue. **Reste** : pas de quota sur les fichiers proposés et non annoncés |
| B8 | Moyenne | Réponses du relais lues sans taille maximale | Corrigé | Lectures bornées : 64 Kio pour les petites réponses, 4 Mio pour une relève, la taille annoncée pour un morceau (`IuFileClient`, `IuMsgClient`) |
| B9 | Faible | Rejeu après redémarrage | Corrigé | Identifiants de message gardés sur disque pendant 9 jours. Âge admis : de 8 jours dans le passé à 10 minutes dans le futur |
| B10 | Faible | Motif d'échec non tronqué (colonne de 2 000 caractères) | Corrigé | Motif assaini et tronqué à 1 000 caractères |
| B11 | Faible | Injection dans les journaux | Corrigé | Motifs venus du réseau assainis avant journalisation |
| B12 | Info | Une URL de réplique venue du pair finit dans les journaux | Accepté | Couvert par l'assainissement de B11 |

### 3.2 Relais (R)

| N° | Gravité | Constat | Statut | Suite donnée |
|---|---|---|---|---|
| R1 | Élevée | Corps de requête non borné, lu avant toute authentification | Corrigé | Création de morceau lue avec une limite de 16 Kio (`413` au-delà). Les autres corps l'étaient déjà |
| R2 | Élevée | Objets orphelins et contournement du quota (courses entre téléversement, suppression et purge) | Corrigé | Un morceau supprimé pendant son téléversement n'est pas marqué reçu, et son contenu est retiré. Le contenu est supprimé avant la ligne en base |
| R3 | Moyenne | Anti-rejeu enregistré avant la vérification de signature | Corrigé | Ordre : fenêtre d'horodatage, puis signature, puis enregistrement. Une requête non signée n'écrit rien en base (voir C6) |
| R4 | Moyenne | Limites de débit par box absentes (exigées par la spécification § 6.4) | En partie | Au plus 2 000 messages en attente d'une même box dans une file (`429`). **Reste** : limite de débit en requêtes par minute |
| R5 | Moyenne | K3s sans TLS, sondes exposées, annuaire en HTTP | En partie | L'entrée K3s est en HTTPS seulement (`websecure`, secret `relais-tls`) et seul `/iu/` est routé. **Reste** : l'adresse de l'annuaire vue du relais, à mettre en HTTPS lors du déploiement réel. Les manifestes modifiés n'ont été vérifiés que pour la syntaxe |
| R6 | Moyenne à faible | La signature n'est pas liée au relais destinataire (rejeu d'une requête vers un autre relais) | Accepté | Le rejeu ne vaut que dans la fenêtre de 5 minutes et vers un relais qui sert la même organisation. Il ne donne accès à aucun contenu. À soumettre à l'auditeur |
| R7 | Faible | Oracle d'état des box avant toute signature | Corrigé | Une box inconnue et une signature invalide reçoivent la même réponse (`401`). L'état n'est révélé qu'après une signature valide |
| R8 | Faible | Renouvellement de file non transactionnel | Corrigé | Une seule transaction. Un second renouvellement simultané est refusé |
| R9 | Faible | Fichiers temporaires abandonnés, espace de `/tmp` | Corrigé | Le fichier temporaire est supprimé quelle que soit l'issue, et ceux de plus de 6 heures sont purgés au démarrage |
| R10 | Faible, à vérifier | Nouvel essai de Traefik et anti-rejeu | Accepté | Le nouvel essai ne vaut qu'avant toute réponse. Une requête rejouée sur une autre instance est refusée comme déjà vue, et la box réessaie avec une nouvelle signature. À vérifier sur un vrai cluster |
| R11 | Faible | Quota non atomique entre instances | Accepté | Dépassement borné par le nombre d'instances × la taille d'un morceau |
| R12 | Info | Compte S3 d'essai (droit `Admin`) déployé par défaut dans K3s, et remarques mineures | En partie | `deploy/k3s/essai` est séparé du déploiement par défaut (voir D10). **Reste** : en production, un compte S3 limité au compartiment |

### 3.3 Console, API et entrée DICOM (L)

| N° | Gravité | Constat | Statut | Suite donnée |
|---|---|---|---|---|
| L1 | Élevée | L'émetteur DICOM n'est authentifié que par son AET (usurpation possible) | Atténué | Liste facultative d'adresses ou de réseaux admis (`imagunity.dicom.limits.allowed-sources`). **Reste** : un poste admis peut prendre n'importe quel AET autorisé. DICOM TLS n'est pas encore proposé. La séparation réseau du site reste nécessaire |
| L2 | Moyenne à élevée | SCP DICOM sans délais ni limite d'associations | Corrigé | Délai de demande d'association (10 s), délai d'inactivité (2 min), au plus 32 associations simultanées |
| L3 | Moyenne | Disque saturable par le PACS : seuil vérifié avant l'écriture, taille non bornée | Corrigé | Instance de 4 Go au plus, en C-STORE comme en STOW-RS. Réglable |
| L4 | Moyenne | HTTP Basic sans protection contre la force brute, et coût bcrypt à chaque requête | Reporté | Console sur le réseau du site seulement. Limitation des essais à ajouter |
| L5 | Moyenne | Défauts permissifs : écoute sur toutes les interfaces, identifiants en clair | Accepté | Avertissement au démarrage si la console ou l'entrée DICOM écoutent partout. Mots de passe hachés acceptés (`{bcrypt}`). Choix de configuration documentés |
| L6 | Faible | Mot de passe généré écrit en clair dans les journaux | Corrigé | Écrit dans un fichier lisible du seul compte de la box (`mot-de-passe-console.txt`, droits 0600) |
| L7 | Faible | Conteneurs lancés en root | Reporté | Voir D3 |
| L8 | Faible, à vérifier | Journaux injectables et inondables avant authentification | En partie | AET venus du réseau assainis dans les journaux. **Reste** : volume de journaux avant authentification |
| L9 | Faible | Flux SSE : un seul fil d'envoi, file non bornée, nombre d'abonnés illimité | Reporté | Réservé aux opérateurs authentifiés |
| L10 | Info | Les messages d'erreur de l'API peuvent contenir des chemins internes | Accepté | Réservé aux opérateurs authentifiés |
| L11 | Info | Remarques de durcissement | Accepté | — |

### 3.4 Service d'annuaire, mises à jour, déploiement (D)

| N° | Gravité | Constat | Statut | Suite donnée |
|---|---|---|---|---|
| D1 | Élevée | Un annuaire compromis peut faire signer au responsable un brouillon piégé | Corrigé | L'outil affiche le document exact et demande de taper « oui ». Numéro de version plafonné à 10⁹ |
| D2 | Moyenne | Retour en arrière de configuration par rétention de documents après le retrait d'un groupe | Corrigé | La box garde les 32 documents vérifiés les plus récents qu'elle a vus, et se replie sur le plus récent d'entre eux qui la vise encore (voir C5) |
| D3 | Moyenne | Conteneurs en root, `/app` modifiable : le processus vérifié peut neutraliser son lanceur | Reporté | Après une exécution de code dans la box, l'attaquant peut réécrire le lanceur (`/app`) ou l'état anti-retour des mises à jour (`/data`). À faire dans les images : compte non privilégié, `/app` en lecture seule, `read_only` et `cap_drop` dans Compose, état hors de portée du processus |
| D4 | Moyenne | Comptes rendus : corps lu en entier avant la limite, sans authentification | Corrigé | Lecture bornée à 64 Kio |
| D5 | Moyenne | Exposition réseau et secrets d'essai dans la pile Compose | Corrigé | Ports publiés sur `127.0.0.1`. Les mots de passe d'essai restent, signalés comme synthétiques |
| D6 | Faible à moyenne | La box lit les réponses de l'annuaire sans borne | Corrigé | Lecture bornée à 32 Mio |
| D7 | Faible | Comptes rendus de box expirées ou pas encore valides acceptés | Corrigé | Seules les box actives à la date de réception rendent compte |
| D8 | Faible | Pas de protection CSRF sur les dépôts hors de `/admin/api/` | Corrigé | Les POST exigent `application/json`, qu'un formulaire d'une autre page ne peut pas envoyer |
| D9 | Faible | Téléchargement d'une mise à jour sans borne | Corrigé | Copie arrêtée à la taille signée, artefact tronqué refusé, manifeste borné à 1 Mio (voir C7) |
| D10 | Faible | Secrets d'essai dans le déploiement K3s par défaut | Corrigé | Base et S3 d'essai sortis du déploiement par défaut (`deploy/k3s/essai`) |
| D11 | Faible | Mot de passe de dépôt généré puis écrit en clair dans le journal | Corrigé | Écrit dans un fichier lisible du seul compte du service |
| D12 | Faible | Chaîne d'approvisionnement : images de base et actions non épinglées par empreinte | Reporté | Actions de la CI épinglées par tag, images de base sans digest, enveloppe Maven sans empreinte. L'analyse OSV des dépendances tourne à chaque intégration. Épinglage à faire |
| D13 | Info | Vérification des mises à jour désactivée sans clé de l'éditeur, et pas de détection de gel | Accepté | Documenté. Un miroir peut geler les mises à jour : manifestes sans date d'expiration |

### 3.5 Cryptographie et documents signés (C)

Aucun défaut de primitive n'a été trouvé : ni réutilisation de nonce, ni hybride vérifié à moitié, ni comparaison de secret sensible au temps.

| N° | Gravité | Constat | Statut | Suite donnée |
|---|---|---|---|---|
| C1 | Moyenne (élevée si deux entrées de même numéro existent) | Une révocation peut être annulée par une entrée jumelle de même numéro | En partie | Box et relais gardent l'empreinte du contenu au plus haut numéro et refusent un autre contenu sous ce numéro. **Reste** : l'outil ne calcule pas le numéro à partir de l'entrée publiée la plus haute, mais le serveur d'annuaire refuse le conflit |
| C2 | Moyenne | Un annuaire compromis peut retenir une révocation pendant toute la validité de l'entrée | En partie | Validité de 90 jours par défaut, date de la dernière lecture de l'annuaire visible dans la console, documentation corrigée. **Reste** : liste de révocation ou preuve de fraîcheur signée |
| C3 | Moyenne | Messages IU-MSG sans destinataire dans la partie signée, et anti-rejeu volatil | Corrigé | Voir B5 et B9 |
| C4 | Moyenne, à vérifier | Lien TPM : aucun PCR par défaut, et la promesse sur les PCR 0 et 7 est exagérée | En partie | Avertissement au démarrage sans PCR, ou sans aucun des registres 4, 8, 9 ou 11. Documentation corrigée, avec `sha256:0,4,7,8,9` recommandé. **Reste** : lien obligatoire en production, politique signée |
| C5 | Faible | Configuration centrale : retour en arrière possible après le retrait d'une box d'un groupe | Corrigé | Voir D2 |
| C6 | Faible | Anti-rejeu du relais enregistré avant la vérification de signature | Corrigé | Voir R3. Espace des identifiants toujours global, identifiants aléatoires de 128 bits |
| C7 | Faible | Téléchargement de mise à jour sans limite de taille | Corrigé | Voir D9 |
| C8 | Faible | Pas de séparation de domaine explicite entre documents JSON signés | Reporté | Pas de confusion possible aujourd'hui : champs disjoints et champs inconnus refusés. Préfixe `IU1-doc ‖ type` prévu pour une prochaine version de suite. À soumettre à l'auditeur |
| C9 | Info | Défense en profondeur : statut des box au niveau du protocole, plafond d'itérations des fichiers de clés, clés en clair lisibles, droits hors POSIX, capacités Ed25519 seules | Accepté | Documenté (dossier § 5 et § 9) |

## 4. Ce que l'auditeur peut tenir pour vérifié

Ces points ont été vérifiés par la revue, sans constat. Ils restent dans le périmètre de l'audit.

- **Primitives et chiffrement** :
  - primitives du JDK seulement ;
  - les deux signatures hybrides sont exigées ;
  - nonces des paquets dérivés du rang du bloc sous une clé par paquet ;
  - dernier bloc marqué dans les données associées.
- **Documents signés** : vérifiés sur les octets exacts, champs inconnus refusés, organisation et type contrôlés.
- **Chemins de fichiers** : construits à partir d'identifiants validés (UID DICOM, AET, noms de partenaires), sans traversée de dossier.
- **Relais** : il ne voit en clair ni le contenu ni les métadonnées DICOM. L'inspection octet par octet de la campagne d'essais l'a confirmé.

## 5. Suites

Les constats marqués « Reste », « Reporté » ou « Accepté » sont signalés à l'auditeur ([dossier § 9 et § 10](dossier-audit.md)). Ceux qu'il jugera prioritaires seront corrigés avant la mise en pilote.
