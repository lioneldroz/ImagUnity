# Phase 1 — Exploration SimpleX

> Objectif : piloter `simplex-chat` depuis Java, mesurer ses limites, et stabiliser le `TransportPort`.
> Statut : **code prêt, essais réels à mener** (voir § 4).

## 1. Ce qui est en place

Module [`backend/transport`](../backend/transport) :

| Élément | Rôle |
|---|---|
| `TransportPort` | Interface du transport vue par le cœur métier : invitation, appairage, message, envoi et réception de fichier, événements. **Brouillon**, à figer après les essais. |
| `TransportEvent` | `PeerConnected`, `MessageReceived`, `FileOffered`, `FileReceived`, `FileSent`, `FileFailed`. |
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

*À compléter après les essais.*
