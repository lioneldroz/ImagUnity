# Déploiement

## Installer ImagUnity

Deux guides, sous Rocky Linux 10 :

| Quoi | Guide | Fichiers |
|---|---|---|
| Site central : relais IU-T, annuaire, serveur de mises à jour (Docker) | [installation du site central](../docs/guides/installation-site-central-rocky10.md) | [`site-central/`](site-central/), plus [`relay/`](relay/) et [`directory/`](directory/) (images) |
| Box d'un établissement (systemd, sans root, mises à jour automatiques) | [installation d'une box](../docs/guides/installation-box-rocky10.md) | [`box-systemd/`](box-systemd/) |

Livrer une version (registre des numéros, construction, signature hors ligne, publication) : [`livraison/`](livraison/) et le [guide de livraison](../docs/guides/livraison.md).

## Banc d'essai

[`banc-essai/`](banc-essai/) n'est **pas une installation** : il rassemble sur une seule machine deux établissements, deux box, le relais IU-T et l'annuaire, pour la campagne de tests et l'audit (données synthétiques uniquement). On y trouve aussi les variantes du banc : plusieurs relais, relais réparti, TPM, manifestes K3s.
