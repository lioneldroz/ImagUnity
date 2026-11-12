# Mises à jour signées

> Objectif (pilote, priorité 3 du [plan](plan-de-realisation.md)) : une box n'exécute que du logiciel **signé par l'éditeur**, avec une clé gardée hors ligne, et ne revient jamais à une version plus ancienne. Une image modifiée, un jar piégé sur un serveur ou une version ancienne remise en place pour rouvrir une faille sont refusés avant le démarrage.
> Statut : **fait**, et vérifié sur la pile Docker (§ 6).

## 1. Principes

```
Éditeur (poste hors ligne)                         Box
  clé de signature des versions          image : lanceur + version livrée (jar + manifeste)
  signe un manifeste par version ──────► /data/mises-a-jour : versions installées, dépôt
  (produit, version, n°, empreinte)      serveur de mises à jour (facultatif)
                                                    │
                                        au démarrage, le lanceur vérifie chaque version,
                                        prend la plus récente, refuse un retour en arrière,
                                        puis démarre le logiciel
```

- **Double signature, clé hors ligne.** L'éditeur signe chaque version avec la suite `IU1`, déjà utilisée pour l'annuaire : Ed25519 et ML-DSA-65 (résistant au calcul quantique), et les deux signatures doivent être valides. Sa clé privée reste sur un poste hors ligne, chiffrée par phrase de passe.
- **Clé épinglée dans la box.** La box connaît la clé publique de l'éditeur. L'installateur confirme son empreinte courte hors bande et la recopie dans la configuration. Sans cette confirmation, la box ne démarre pas.
- **Le lanceur ne se met pas à jour lui-même.** Le lanceur (`imagunity-release.jar`) fait partie de l'image. Il vérifie le logiciel de la box (`imagunity-box.jar`), qui seul est mis à jour par ce mécanisme. Le lanceur et l'image se mettent à jour par une nouvelle image.
- **Anti-retour en arrière.** Chaque version porte un **numéro** strictement croissant. La box retient le numéro de la dernière version exécutée et refuse de démarrer sur une version de numéro inférieur.
- Uniquement des primitives standard du JDK 25.

## 2. Manifeste de version

À côté de l'artefact, `<artefact>.manifeste.json` est un document signé, avec la même enveloppe que les pièces de l'annuaire :

```json
{ "payload": "{\"suite\":\"IU1\",\"product\":\"imagunity-box\",\"version\":\"0.2.0\",\"sequence\":2,
                \"artifact\":{\"name\":\"imagunity-box-0.2.0.jar\",\"size\":43379372,\"sha256\":\"…\"},
                \"releasedAt\":\"2026-11-12T13:25:00Z\"}",
  "signatures": { "suite": "IU1", "ed25519": "…", "mlDsa65": "…" } }
```

Une version est valide si :
1. les deux signatures sont celles de la clé épinglée de l'éditeur ;
2. le manifeste concerne ce produit (`imagunity-box`) ;
3. l'artefact a exactement la taille et l'empreinte SHA-256 signées.

## 3. Outil de l'éditeur

```bash
R="java -jar backend/release/target/imagunity-release.jar"
$R cle-creer --editeur "ImagUnity" --cles editeur.json          # + editeur.json.pub.json, empreinte affichée
$R empreinte --cle-publique editeur.json.pub.json
$R signer    --cles editeur.json --artefact imagunity-box.jar --version 0.2.0 --sequence 2
$R verifier  --cle-publique editeur.json.pub.json --manifeste imagunity-box.jar.manifeste.json --artefact imagunity-box.jar
```

- La phrase de passe vient de `IMAGUNITY_PHRASE_EDITEUR`. Sinon, elle est saisie au terminal, deux fois à la création. `--sans-phrase` crée une clé en clair, sur choix explicite (déconseillé).
- `signer` écrit le manifeste à côté de l'artefact (`--sortie` pour un autre emplacement).
- L'éditeur tient le registre des numéros : chaque version reçoit le numéro suivant.

## 4. Dans la box

L'image démarre par `demarrer.sh`. Le lanceur choisit la version, puis le script la démarre (`exec`, si bien que le logiciel reste le processus principal du conteneur). Configuration :

| Variable | Rôle |
|---|---|
| `IMAGUNITY_RELEASE_KEY` | Clé publique de l'éditeur. Sans elle, la vérification est **désactivée** : la version livrée démarre telle quelle, avec un avertissement (développement, essais). |
| `IMAGUNITY_RELEASE_CODE` | Son empreinte courte, vérifiée hors bande. Si elle ne correspond pas, la box ne démarre pas. |
| `IMAGUNITY_RELEASE_DIR` | Dossier des versions (défaut `/data/mises-a-jour`) |
| `IMAGUNITY_UPDATE_URL` | Serveur de mises à jour, facultatif |

`deploy/compose.mises-a-jour.yml` configure les deux box du Compose (clé dans `mises-a-jour/editeur.pub.json`, non versionnée).

**Arrivée d'une nouvelle version**, au démarrage :
- **Dépôt manuel**, pour une box sans accès sortant : copier `<artefact>` et `<artefact>.manifeste.json` dans `/data/mises-a-jour/depot/`, puis redémarrer la box. Une version valide est installée. Une pièce refusée est mise de côté dans `depot/refusees/`, avec la cause dans le journal.
- **Serveur de mises à jour** : la box lit `<url>/latest.json` (manifeste signé de la dernière version). Si elle est plus récente, elle télécharge `<url>/<artefact>`. N'importe quel serveur HTTP statique convient : il n'est pas de confiance, puisque tout est vérifié. Si le serveur est injoignable, la box démarre sur ce qu'elle a.

Une version est installée d'un coup : copiée, revérifiée, puis son dossier `versions/<n°>/` est renommé.

**Choix de la version.** Parmi la version livrée avec l'image et les versions installées, la box prend la version **valide** de numéro le plus haut :
- une version installée altérée sur le disque est écartée ;
- si la meilleure version valide a un numéro inférieur à la dernière exécutée, la box **refuse de démarrer** (« Retour en arrière refusé… ») ;
- aucune version valide : la box refuse aussi de démarrer ;
- les versions installées plus anciennes que la version choisie sont supprimées.

**Console.** L'en-tête affiche la version et son numéro, par exemple « box-b · version 0.2.0 (signée, n° 2) », ou « (non vérifiée) ».

**Images.** `deploy/campagne/box.Dockerfile` construit l'image à partir du jar signé et de son manifeste : la version livrée est vérifiable. `deploy/box/Dockerfile` construit depuis les sources, donc sans signature. Avec la clé de l'éditeur configurée, une telle image ne démarre que sur une version signée déposée ou téléchargée.

## 5. Ce que cela protège, et ce que cela ne protège pas

- **Protège** contre :
  - un jar modifié dans l'image, dans le dépôt ou sur le serveur de mises à jour ;
  - une version signée par une autre clé ;
  - une version ancienne remise en place, même si elle est authentique ;
  - un serveur de mises à jour compromis : il peut seulement retenir les nouvelles versions.
- **Ne protège pas** contre :
  - un attaquant qui contrôle l'hôte ou l'image elle-même : il peut remplacer le lanceur ;
  - une écriture sur `/data` entre la vérification et le démarrage.

  Contre ces risques, il faut un démarrage mesuré (TPM) et des images signées, au niveau de la plateforme.
- **Le gel est visible, pas empêché.** Un serveur peut cacher qu'une version plus récente existe. Les manifestes n'ont pas de date d'expiration ; la console montre la version en service, et l'exploitant la compare à celle annoncée par l'éditeur.
- **Clé de l'éditeur compromise** : il faut une nouvelle clé, installée dans les box par une nouvelle image et une nouvelle confirmation d'empreinte. La rotation automatique de clé n'est pas prévue.

## 6. Vérifié en réel (pile Docker, novembre 2026)

Avec une clé d'éditeur d'essai, sur la chaîne de la campagne de tests :
- image construite avec la version 0.1.0 (n° 1) signée : les deux box démarrent dessus, « signature vérifiée », et la console l'affiche ;
- dépôt dans la box B d'une version 0.2.0 (n° 2) signée, et d'une 0.3.0 (n° 3) **altérée après signature**. Au redémarrage, la n° 2 est installée, la n° 3 est refusée (« Artefact différent du manifeste signé (empreinte) ») et mise de côté. B tourne sur la n° 2, en processus principal du conteneur ;
- transfert nominal (100 instances) de A vers B après la mise à jour : ACKNOWLEDGED, **100 instances identiques sur 100** ;
- **retour en arrière** : la n° 2 supprimée du disque, B refuse de démarrer sur la n° 1 de l'image (« Retour en arrière refusé : la box a déjà exécuté la version 0.2.0 (n° 2)… ») ;
- empreinte de l'éditeur non confirmée : B refuse de démarrer ;
- redépôt de la n° 2, et dépôt d'une n° 9 **signée par une autre clé** : la n° 2 est réinstallée, la n° 9 refusée (« Manifeste non signé par la clé de l'éditeur »), et B redémarre sur la n° 2.

Le téléchargement depuis un serveur de mises à jour est couvert par les tests automatiques (serveur HTTP local : version publiée, absente, altérée sur le serveur, déjà installée), pas par l'essai Docker.

## 7. Reste pour le pilote

- **Images signées** (signature de l'image par l'éditeur, vérifiée par l'hôte) et démarrage mesuré, pour couvrir le lanceur lui-même.
- **Clé de l'éditeur** sur support matériel, et procédure de rotation.
- **Signature à plusieurs** (par exemple éditeur et responsable sécurité), si l'organisation l'exige.
- **Mise à jour sans redémarrage manuel** : la box vérifie périodiquement le serveur et redémarre d'elle-même dans une fenêtre de maintenance.
