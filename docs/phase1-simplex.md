# Phase 1 — Exploration SimpleX

> Objectif : piloter `simplex-chat` depuis Java, mesurer ses limites, et stabiliser le `TransportPort`.
> Statut : **premiers essais réels réussis** le 2026-11-11 sur les relais publics de SimpleX (voir § 5). Reste à mesurer sur relais auto-hébergés : reprise après coupure, destinataire hors ligne, fichiers de plus de 1 Go.

## 1. Ce qui est en place

Module [`backend/transport`](../backend/transport) :

| Élément | Rôle |
|---|---|
| `TransportPort` | Interface du transport vue par le cœur métier : invitation, appairage, message, envoi et réception de fichier, événements. **Brouillon**, à figer après les essais. |
| `TransportEvent` | `PeerConnected`, `MessageReceived`, `FileOffered`, `FileReceived`, `FileSent`, `FileFailed`, `FileProgress`. |
| `simplex.SimplexChatClient` | Client bas niveau de l'API WebSocket : corrélation commande / réponse par `corrId`, erreurs `chatCmdError`, délai d'attente, diffusion des événements. |
| `simplex.SimplexTransport` | Implémentation du `TransportPort` sur `simplex-chat`. |
| `simplex.SimplexEventMapper` | Traduction des événements `simplex-chat` en `TransportEvent`. |
| `SimplexLiveTest` | Essai sur deux vrais clients : appairage, message, fichier aléatoire avec contrôle SHA-256, temps mesurés. Ignoré si les clients ne sont pas lancés. |

Aucun code SimpleX n'est lié : le client tourne comme un processus séparé (licence AGPL-3.0).

## 2. Ce que dit la documentation de l'API (simplex-chat v7.1.2)

Source : `bots/` du dépôt `simplex-chat` (README, `api/COMMANDS.md`, `api/EVENTS.md`, `api/TYPES.md`).

- **Protocole** : messages texte JSON. Commande `{"corrId", "cmd"}`, réponse `{"corrId", "resp"}`, événement `{"resp"}` sans `corrId`. Les types inconnus doivent être ignorés.
- **Sécurité** : l'API n'est ni authentifiée ni chiffrée ; le CLI n'écoute que sur **localhost**. La box et le client doivent donc partager la même machine ou la même pile réseau (c'est le montage `network_mode: service:chat-x` du Compose).
- **Commandes utilisées** :

| Usage | Commande | Réponse attendue |
|---|---|---|
| Profil actif | `/user` | `activeUser` |
| Créer une invitation | `/_connect <userId>` | `invitation` (`connLinkInvitation.connFullLink`) |
| Rejoindre | `/connect <lien>` | `sentConfirmation` |
| Message / fichier | `/_send @<contactId> json [ComposedMessage]` | `newChatItems` |
| Accepter un fichier | `/freceive <fileId> <chemin>` | `rcvFileAccepted` |

- **Événements utilisés** : `contactConnected`, `contactSndReady`, `newChatItems`, `rcvFileComplete`, `sndFileCompleteXFTP`, `rcvFileError`, `sndFileError`.
- Il n'y a pas de commande d'envoi de fichier à part : un fichier part dans un message composé (`fileSource.filePath`).
- Le chemin de `/freceive` ne peut pas contenir d'espace (pas de guillemets dans la syntaxe) : la box doit utiliser un dossier de travail sans espace.

## 3. Questions à trancher par les essais

| Question | Comment la mesurer |
|---|---|
| Lequel de `contactConnected` / `contactSndReady` arrive chez chaque partie, et quand peut-on envoyer ? | Journal des événements de `SimplexLiveTest` |
| Limite de taille d'un fichier XFTP (1 Go en v5.0.0, valeur actuelle ?) | `IMAGUNITY_SIMPLEX_FILE_MB` à 500, 1000, 1100 |
| Débit et temps d'un transfert | Temps affichés `[phase 1]` |
| Reprise après coupure réseau en cours de transfert | Couper la liaison d'un client pendant un gros envoi |
| Box réceptrice éteinte pendant l'envoi | Arrêter le client B, envoyer, le relancer |
| Le fichier reçu est-il déchiffré sur disque, et où ? | Vérifier `FileReceived.path` et `encrypt=off` éventuel |
| Progression des transferts (pour la console) | Événements non documentés `sndFileProgressXFTP` / `rcvFileProgressXFTP` |

## 4. Environnement d'essai

Les clients `simplex-chat` existent pour Windows et Linux, mais les relais `smp-server` et `xftp-server` **ne tournent que sous Linux**. Trois possibilités :

1. **Docker Desktop (ou WSL)** sur le poste de développement, puis `docker compose up` de [deploy/](../deploy/) : relais auto-hébergés, comme prévu par la note.
2. **Une machine virtuelle Linux** qui héberge les deux relais ; les deux clients tournent sur le poste.
3. **Les relais publics de SimpleX** (préconfigurés dans le client) pour un premier essai de l'API, avec des fichiers aléatoires uniquement. Ne valide pas l'auto-hébergement.

### Lancer l'essai (clients sur le poste)

```bash
simplex-chat -d ./sx-a --create-bot-display-name box-a --create-bot-allow-files -p 5225 --files-folder ./sx-a-files
simplex-chat -d ./sx-b --create-bot-display-name box-b --create-bot-allow-files -p 5226 --files-folder ./sx-b-files
```

Avec des relais auto-hébergés, ajouter à chaque client `--server smp://<empreinte>@<hôte> --xftp-server xftp://<empreinte>@<hôte>`.

Puis, dans `backend/` :

```bash
IMAGUNITY_SIMPLEX_A=ws://localhost:5225 IMAGUNITY_SIMPLEX_B=ws://localhost:5226 ./mvnw -pl transport -am test -Dtest=SimplexLiveTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 5. Résultats

### 5.1 Conditions

- 2026-11-11, poste Windows 11, client `simplex-chat` **v7.1.2** pour Windows (empreinte SHA-256 du binaire vérifiée par rapport à la version publiée sur GitHub).
- Deux clients sur le même poste (`-p 5225` et `-p 5226`), **relais publics préconfigurés de SimpleX** (option 3 du § 4).
- **Fichiers aléatoires uniquement**, aucune donnée de santé. Les essais volumineux ne sont pas faits sur les relais publics, par égard pour ce service gratuit.
- Connexion Internet du poste ; les temps dépendent de cette liaison et de la charge des relais publics, ils ne valent que comme ordre de grandeur.

### 5.2 Mesures

| Étape | 5 Mo | 50 Mo |
|---|---|---|
| Appairage (invitation → `contactConnected` des deux côtés) | 2,2 s | 1,0 s |
| Message SMP (envoi → réception) | 0,14 s | 0,17 s |
| Fichier (envoi → réception complète chez B) | 9,1 s | 26,6 s (≈ 1,9 Mo/s) |
| Intégrité (SHA-256 source = reçu) | identique | identique |

### 5.3 Réponses aux questions du § 3

| Question | Réponse |
|---|---|
| `contactConnected` / `contactSndReady` ? | Pour un lien d'invitation, **seul `contactConnected`** arrive, chez les deux parties, précédé de `contactConnecting`. `contactSndReady` n'a pas été émis. L'envoi est possible dès `contactConnected`. |
| Déroulé d'un envoi de fichier | **En deux temps** : A téléverse tout le fichier (`sndFileProgressXFTP` puis `sndFileCompleteXFTP`), et c'est seulement ensuite que B reçoit la description (`rcvFileDescrReady`), puis télécharge (`rcvFileStart`, `rcvFileProgressXFTP`, `rcvFileComplete`). Le temps total est donc la somme du téléversement et du téléchargement. |
| Progression des transferts | Les événements **non documentés** `sndFileProgressXFTP` (`sentSize`, `totalSize`) et `rcvFileProgressXFTP` (`receivedSize`, `totalSize`) existent, environ un tous les 3 Mo sur 50 Mo. Forme relevée dans `src/Simplex/Chat/Controller.hs`. Traduits en `FileProgress`. |
| Fichier reçu : où, et en clair ? | À l'**emplacement exact** passé à `/freceive`, **en clair** (empreinte identique à la source). |
| Limite de taille | D'après le code source (`simplexmq`, `Simplex/FileTransfer/Description.hs` et `Agent.hs`) : **1 Go « souple »** (`maxFileSize`) et **5 Go « dur »** côté agent (`maxFileSizeHard`). Morceaux XFTP de 64 Kio, 256 Kio, 1 Mio ou 4 Mio. Les fichiers de plus de 1 Go restent à essayer, sur relais auto-hébergés. |
| Autres événements observés | `chatItemsStatusesUpdated` (statuts de remise des messages envoyés), `newChatItems` pour les événements de connexion. Tous ignorés sans erreur par l'adaptateur. |
| Reprise après coupure, destinataire hors ligne | **Non mesurés** : à faire sur relais auto-hébergés (scénarios de la phase 8). |

### 5.4 Conséquences

- L'adaptateur fonctionne **sans modification** sur un vrai `simplex-chat` : le `TransportPort` peut être stabilisé tel quel pour la suite de la preuve de concept.
- **Fait :** le `TransportEvent` a gagné l'événement `FileProgress` (sens, octets transférés, total), alimenté par `sndFileProgressXFTP` / `rcvFileProgressXFTP` et vérifié par l'essai réel. Le total est celui vu par le transport : 6 Mio pour un fichier de 5 Mio (morceaux complétés). Seul le rapport transféré / total est significatif.
- **Fait :** le déroulé en deux temps double le temps d'un transfert par rapport à un flux continu. La [spécification IU-T](specification-protocole.md) prévoit désormais une **annonce anticipée** (§ 6.6, v0.2) : le destinataire télécharge pendant le téléversement.
- Le **découpage par série** (phase 3) reste nécessaire pour les études de plus de 1 Go.

### 5.5 Remarque sur l'environnement de développement

Sur le poste, le JDK refuse d'ouvrir son tube interne (socket Unix) dans le dossier temporaire par défaut (`Unable to establish loopback connection`). C'est lié au bac à sable de l'outil utilisé pour ces essais, pas au code. Contournement si nécessaire : `-Djdk.net.unixdomain.tmpdir=<dossier inscriptible>`, de préférence par la variable d'environnement `JDK_JAVA_OPTIONS`. Ne pas passer par `-DargLine=...` : cela remplacerait l'agent JaCoCo et fausserait la mesure de couverture.
