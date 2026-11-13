# Notes de version

> Ce qu'il faut savoir avant de mettre à jour un site central ou un parc de box. L'ordre général reste : **l'annuaire, puis le relais, puis les box** ([livraison, § 6](livraison.md#6-mettre-à-jour-le-site-central-depuis-une-livraison)).

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
