# Notes de version

> Ce qu'il faut savoir avant de mettre à jour un site central ou un parc de box. L'ordre général reste : **l'annuaire, puis le relais, puis les box** ([livraison, § 6](livraison.md#6-mettre-à-jour-le-site-central-depuis-une-livraison)).

## Version suivante (en préparation)

Corrections issues de la revue de novembre 2026 :

- **Séries arrivées après l'envoi de leur étude** : elles étaient supprimées sans être envoyées. Chaque transfert enregistre désormais ses instances ; une série arrivée plus tard part dans un nouveau transfert. Migration de la base de la box (`V8`), appliquée seule au démarrage. Les transferts en cours lors de la mise à jour n'ont pas encore cette liste : si une instance d'un tel transfert revient (arrêt de la box juste après l'empaquetage), elle est renvoyée une seconde fois au lieu d'être supprimée ; elle n'est jamais perdue.
- **Relais** : la demande de création de file est limitée à 16 Ko, lue avant l'authentification (une requête démesurée pouvait épuiser la mémoire du relais).
- **Box sous systemd** : le choix de la version au démarrage ne télécharge plus rien (c'est la mise à jour de la nuit qui télécharge, puis redémarre la box). Un démarrage ne dépend donc plus du réseau, et son échec déclenche l'alerte. Ces fichiers ne sont pas dans le jar signé : relancer `installer.sh` de la nouvelle archive, qui recharge systemd.
- **Lecture de l'annuaire** : une réponse mal formée ou une erreur imprévue n'arrête plus la lecture périodique jusqu'au redémarrage de la box.
- **Création de la file de réception** : une demande répétée par la même box pour son adresse réservée (réponse du relais perdue) rend la file existante et ses messages, au lieu d'en créer une autre que personne n'utilise. Correction côté relais : sans effet tant que le relais n'est pas mis à jour.
- **Restitution au PACS** : délais d'attente (connexion et association 30 s, chaque C-STORE 5 min, fin d'association 30 s). Un PACS figé ne bloque plus les envois et réceptions de la box.
- **Réception interrompue** : un arrêt de la box pendant le dépaquetage d'un paquet ne fait plus échouer le transfert au redémarrage.
- **Clés protégées par le TPM** : les deux fichiers du secret scellé sont remplacés sans risque de mélange en cas de coupure de courant.
- **Sauvegarde du site central** : `sauvegarder.sh` sauvegarde aussi les données de Caddy, dont l'autorité de certification interne, et accepte un dossier de destination relatif.
- **Console** : le flux en direct se rouvre de lui-même après une erreur HTTP (par exemple pendant le redémarrage de la box derrière un proxy).
- **Transport** : téléchargements simultanés sans risque d'erreur de concurrence au démarrage.

## 0.15.0

Première version livrée par la [chaîne de livraison](livraison.md) (n° 30). Elle apporte la console dans la box, l'installation de la box sous systemd et celle du site central sous Docker.

**Code de migration retiré.** Deux passerelles d'anciennes versions disparaissent :

| Retiré | Qui est concerné | À faire **avant** de passer en 0.15.0 |
|---|---|---|
| Reprise des partenaires appairés par SimpleX, au premier démarrage sur IU-T | Box encore sur SimpleX | Mettre d'abord la box en dernière version 0.14.x, la démarrer une fois sur IU-T : ses partenaires sont repris sous leur nom |
| Mode « paquets non chiffrés » (`IMAGUNITY_DIRECTORY_ENCRYPTION_REQUIRED=false`) | Parc dont des entrées d'annuaire ne publient pas encore de clés de chiffrement | Réémettre ces entrées ([chiffrement applicatif, § 5](../reference/chiffrement-applicatif.md#5-migrer-un-parc-existant)), puis retirer le réglage |

Une box 0.15.0 qui porte encore `IMAGUNITY_DIRECTORY_ENCRYPTION_REQUIRED=false` **refuse de démarrer**, avec un message qui l'explique, au lieu d'ignorer le réglage. Avec `true`, ou sans la ligne, rien ne change.

Une box passée directement de SimpleX à 0.15.0 démarre, mais ses partenaires SimpleX ne sont pas repris : il faut les appairer de nouveau depuis la console.

**Conservé** : la protection des clés par phrase de passe et son passage au TPM ([installation d'une box, § 7](installation-box-rocky10.md#7-protéger-les-clés-par-le-tpm-recommandé)). Les identifiants locaux des contacts IU-T commencent toujours à 1 000 000.
