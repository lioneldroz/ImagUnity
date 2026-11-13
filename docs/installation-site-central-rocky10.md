# Installation du site central sur Rocky Linux 10 (Docker)

> Le **site central** d'une organisation ImagUnity réunit :
> - le **relais IU-T**, boîte aux lettres aveugle par laquelle passent les examens chiffrés ;
> - l'**annuaire**, qui publie les box enrôlées, signées par le responsable ;
> - le **serveur de mises à jour** des box.
>
> Il tourne sous Docker, derrière Caddy, qui porte le TLS. Les box des hôpitaux s'y connectent en sortie ; leur installation fait l'objet d'un guide à part : [installation d'une box](installation-box-rocky10.md).
>
> Niveau **pré-production** : vrais mots de passe, TLS et sauvegardes, mais **données exclusivement synthétiques** tant que l'[audit de sécurité](audit/dossier-audit.md) n'est pas fait.

```
                      Internet / réseau de l'organisation
   box des sites ─────────────── HTTPS 443 ──────────────┐
   poste d'administration ───── HTTPS 443 (RESEAU_ADMIN)─┤
                                                        ▼
┌─────────────────────────── machine du site central ───────────────────────────┐
│  caddy (80, 443) ── relais.<domaine>   /iu/*            ──► relay ──► relay-db │
│        │           └ annuaire.<domaine> /directory/v1/*  ──► directory ──► directory-db │
│        │                                /admin*  (RESEAU_ADMIN seulement)     │
│        └─────────────────────────────── /mises-a-jour/*  (fichiers statiques) │
└────────────────────────────────────────────────────────────────────────────────┘
```

Fichiers utilisés : [`deploy/site-central/`](../deploy/site-central/) (`compose.yml`, `Caddyfile`, `exemple.env`, `sauvegarder.sh`).

Les mêmes fichiers ont servi à un essai réel :
- TLS par l'autorité interne de Caddy ;
- filtrage de l'administration ;
- deux box enrôlées, dont une installée sous systemd, qui créent leur file sur le relais en HTTPS ;
- transfert de 100 images arrivées identiques ;
- mise à jour automatique d'une box par le serveur de mises à jour ;
- sauvegarde et relecture des bases.

N'ont pas été essayés : Let's Encrypt (pas de nom public dans l'environnement d'essai), la construction des images à partir des sources sur la machine (images construites autrement pour l'essai), et Rocky Linux 10 lui-même.

## 0. Prérequis

| | Conseillé | Remarque |
|---|---|---|
| Système | Rocky Linux 10, x86_64, installation minimale | Machine dédiée |
| Processeur, mémoire | 2 vCPU, 4 Go | La construction des images demande davantage pendant quelques minutes |
| Disque | 50 Go et plus | Les morceaux en attente sur le relais occupent de la place : quota de 100 Go par box au plus, purgés après 72 h |
| Noms DNS | `relais.<domaine>` et `annuaire.<domaine>`, vers cette machine | Les box les utilisent ; ils figurent dans les entrées signées |
| Réseau entrant | TCP 443 depuis les box et le poste d'administration ; TCP 80 et 443 depuis Internet pour Let's Encrypt | Rien d'autre n'est publié |
| Heure | Synchronisée (chrony) | Requêtes signées refusées au-delà de 5 minutes d'écart |

## 1. Préparer le système

```bash
dnf -y update
dnf -y install git dnf-plugins-core openssl
systemctl enable --now chronyd
firewall-cmd --permanent --add-service=https --add-service=http
firewall-cmd --reload
```

## 2. Installer Docker CE

Podman, fourni par Rocky, entre en conflit avec Docker : on le retire.

```bash
dnf -y remove podman buildah runc podman-docker 2>/dev/null || true
dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
dnf -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker compose version
```

## 3. Récupérer ImagUnity et construire les images

```bash
git clone https://github.com/lioneldroz/ImagUnity.git /opt/imagunity     # jeton en lecture seule si le dépôt est privé
cd /opt/imagunity/deploy/site-central
cp exemple.env .env && chmod 600 .env
mkdir -p config mises-a-jour/pilote mises-a-jour/general
docker compose build          # 15 à 30 minutes la première fois
```

`.env`, `config/` et `mises-a-jour/` sont propres à la machine et ignorés par Git.

## 4. Créer la clé du responsable de l'organisation

Le responsable signe les entrées des box et les configurations centrales : sa clé est la racine de confiance de l'organisation.
- **En production**, elle se crée et se garde sur un **poste hors ligne**, sur support amovible chiffré. Sur ce site central ne passent alors que la clé publique et les entrées signées.
- **En pré-production**, on peut la créer ici, dans `/root/imagunity-responsable` (0700).

L'outil du responsable se lance depuis l'image de l'annuaire. On définit la commande `admin` dans le shell :

```bash
mkdir -p /root/imagunity-responsable && chmod 700 /root/imagunity-responsable && cd /root/imagunity-responsable
admin() {
  docker run --rm -i --network imagunity-central_interne -v "$PWD":/travail -w /travail -e LANG=C.UTF-8 \
    -e IMAGUNITY_PHRASE_ORGANISATION -e IMAGUNITY_ANNUAIRE_COMPTE \
    --entrypoint java imagunity/directory:local \
    -cp /app/imagunity-directory.jar -Dloader.main=com.imagunity.directory.DirectoryAdmin \
    org.springframework.boot.loader.launch.PropertiesLauncher "$@"
}
read -rs -p "Phrase de passe du responsable (16 caractères au moins) : " IMAGUNITY_PHRASE_ORGANISATION; echo
export IMAGUNITY_PHRASE_ORGANISATION
admin organisation-creer --organisation <identifiant-organisation> --cles organisation.json
cp organisation.json.pub.json /opt/imagunity/deploy/site-central/config/organisation.pub.json
```

Avant le premier `docker compose up`, le réseau `imagunity-central_interne` n'existe pas encore : la commande affiche une erreur de réseau. Pour `organisation-creer` seulement, remplace `--network imagunity-central_interne` par `--network none`.

L'outil affiche l'**empreinte** de la clé (`XXXX-XXXX-…`). Elle se communique **de vive voix** aux installateurs des box et des relais, qui la recopient dans leur configuration.

## 5. Configurer

Dans `/opt/imagunity/deploy/site-central/.env` :

| Variable | Valeur |
|---|---|
| `RELAIS_HOTE`, `ANNUAIRE_HOTE` | Les deux noms DNS |
| `RESEAU_ADMIN` | Adresses autorisées pour l'interface d'administration et le dépôt des entrées, en CIDR séparés par des espaces (par exemple `192.168.1.0/24 10.0.5.12/32`) |
| `TLS_INTERNE` | Vide : certificats Let's Encrypt. `local_certs` : autorité interne de Caddy (§ 7) |
| `EMPREINTE_RESPONSABLE` | Empreinte du § 4 |
| `RELAIS_DB_MOT_DE_PASSE`, `ANNUAIRE_DB_MOT_DE_PASSE`, `ANNUAIRE_MOT_DE_PASSE` | Chacun tiré au hasard : `openssl rand -base64 24` |
| `RELAIS_QUOTA_PAR_BOX` | Volume de morceaux en attente par box (par défaut `100GB`) |

`ANNUAIRE_MOT_DE_PASSE` est celui du compte de dépôt du responsable (`ANNUAIRE_COMPTE`, par défaut `responsable`). Il sert à publier les entrées et à ouvrir l'interface d'administration.

## 6. Démarrer et vérifier

```bash
cd /opt/imagunity/deploy/site-central
docker compose up -d
docker compose ps        # relay-db et directory-db « healthy », les autres « Up »
```

Contrôles depuis une machine du réseau d'administration (`A` = nom de l'annuaire, `R` = nom du relais) :

| Requête | Attendu | Sens |
|---|---|---|
| `curl -s -o /dev/null -w '%{http_code}' https://A/directory/v1/entries` | `200` | Lecture publique de l'annuaire |
| `… https://A/admin/` | `401` (ou `403` hors `RESEAU_ADMIN`) | Administration protégée |
| `… https://A/actuator/health` et `… https://R/actuator/health` | `404` | Sondes internes non exposées |
| `… https://R/iu/v1/queues` | `405` | API du relais joignable |

## 7. Certificats

**A. Let's Encrypt** (recommandé dès qu'il y a des noms publics). Laisser `TLS_INTERNE` vide. Ouvrir 80 et 443 depuis Internet, et faire pointer les noms DNS sur cette machine. Caddy obtient et renouvelle seul les certificats. Les box n'ont rien à installer.

**B. Autorité interne de Caddy** (réseau privé, sans nom public). Mettre `TLS_INTERNE=local_certs`, puis `docker compose up -d caddy`. Il faut ensuite remettre le certificat racine aux installateurs des box, avec son empreinte, qu'ils vérifient de vive voix :

```bash
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./autorite-interne.crt
openssl x509 -noout -fingerprint -sha256 -in autorite-interne.crt
```

Chaque box l'importe dans son magasin de confiance ([guide de la box, § 5](installation-box-rocky10.md)). La racine reste valable 10 ans dans le volume `caddy-data`, à sauvegarder : la perdre oblige à redistribuer une racine à toutes les box.

**C. Certificats de l'établissement.** Monter les fichiers dans le conteneur `caddy`, par exemple `./certs:/certs:ro`. Ajouter `tls /certs/relais.crt /certs/relais.key` dans le bloc du relais du `Caddyfile`, et de même pour l'annuaire. Si l'autorité est interne, les box l'importent comme en B.

## 8. Enrôler une box

L'installateur d'un site envoie au responsable la **demande d'enrôlement** de sa box. C'est un fichier JSON, sans secret, qui peut passer par courriel. L'installateur lui communique aussi, de vive voix, l'empreinte de la box.

```bash
cd /root/imagunity-responsable
export IMAGUNITY_ANNUAIRE_COMPTE=responsable:<ANNUAIRE_MOT_DE_PASSE>
admin enroler --cles organisation.json --demande demande-site-a.json --sortie entree-site-a.json
#   → « Vérifiez hors bande l'empreinte de la box avec son installateur : XXXX-… » : la comparer avec l'installateur
admin publier --annuaire http://directory:8090 --entree entree-site-a.json
```

La publication passe ici par le réseau interne du site central. Depuis un poste du réseau d'administration, on peut aussi publier par `--annuaire https://<annuaire>` avec l'outil installé sur ce poste.

**Délais.** Le relais relit l'annuaire toutes les 5 minutes. La box crée sa file de réception au plus tard 5 minutes après la publication ; son journal affiche alors « File de réception IU-T créée ». En attendant, il affiche des refus `401` sur le relais : c'est normal.

Options utiles de `enroler` :
- `--validite-jours`, 90 par défaut : à renouveler avant échéance, par une nouvelle entrée de numéro supérieur (`--serie 2`, puis 3…) ;
- `--groupes imagerie-nord,urgences`, pour la [configuration centralisée](configuration-centralisee.md).

**Révoquer une box** (perdue, volée, retirée) :

```bash
admin revoquer --cles organisation.json --entree entree-site-a.json --motif "Box volée" --sortie revocation-site-a.json
admin publier --annuaire http://directory:8090 --entree revocation-site-a.json
```

Le relais et les autres box la refusent dès leur lecture suivante de l'annuaire.

## 9. Publier les mises à jour des box

Les box se mettent à jour seules : chaque nuit, elles interrogent `https://<annuaire>/mises-a-jour/<canal>/latest.json` ([guide de la box, § 10](installation-box-rocky10.md)). Le serveur n'est pas de confiance, puisque chaque box vérifie la signature de l'éditeur. Il sert seulement les fichiers de `deploy/site-central/mises-a-jour/`.

Deux canaux permettent un déploiement progressif : `pilote` pour quelques box choisies, `general` pour toutes les autres.

L'éditeur signe la version sur son poste hors ligne ([mises à jour signées](mises-a-jour-signees.md)) :

```bash
java -jar imagunity-release.jar signer --cles editeur.json --artefact imagunity-box.jar \
    --version 0.15.0 --sequence 28 --sortie latest.json
```

Puis on dépose, **le jar d'abord, le manifeste ensuite** :

```bash
cp imagunity-box.jar /opt/imagunity/deploy/site-central/mises-a-jour/pilote/
cp latest.json       /opt/imagunity/deploy/site-central/mises-a-jour/pilote/
# Après quelques jours sans incident sur les box pilotes :
cp mises-a-jour/pilote/imagunity-box.jar mises-a-jour/pilote/latest.json mises-a-jour/general/
```

Le nom du jar déposé doit être celui que porte le manifeste (`artifact.name`), c'est-à-dire le nom du fichier au moment de la signature.

**Une version défectueuse ne se retire pas** : les box refusent tout retour en arrière. On publie une version corrigée de numéro supérieur.

## 10. Administration et configuration centralisée

L'interface `https://<annuaire>/admin/` n'est ouverte que depuis `RESEAU_ADMIN`, avec le compte `ANNUAIRE_COMPTE`. Elle montre les box, la version de configuration que chacune applique, et permet de préparer les règles de priorité et de routage. Ces règles se signent avec l'outil du responsable (`admin configuration-signer …`) avant d'être publiées : [configuration centralisée](configuration-centralisee.md).

## 11. Sauvegardes

À sauvegarder :
- les bases de l'annuaire et du relais ;
- `.env`, `config/` et le `Caddyfile` ;
- le volume `caddy-data`, si l'autorité interne est utilisée.

Les morceaux du relais sont chiffrés et temporaires : ils ne se sauvegardent pas. La clé du responsable se sauvegarde **hors de cette machine**.

```bash
sh /opt/imagunity/deploy/site-central/sauvegarder.sh /srv/sauvegardes/imagunity   # garde 30 jours
```

Chaque nuit, par un timer systemd :

```bash
cat > /etc/systemd/system/imagunity-sauvegarde.service <<'EOF'
[Unit]
Description=Sauvegarde du site central ImagUnity
[Service]
Type=oneshot
ExecStart=/bin/sh /opt/imagunity/deploy/site-central/sauvegarder.sh /srv/sauvegardes/imagunity
EOF
cat > /etc/systemd/system/imagunity-sauvegarde.timer <<'EOF'
[Unit]
Description=Sauvegarde nocturne du site central ImagUnity
[Timer]
OnCalendar=*-*-* 02:15:00
Persistent=true
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload && systemctl enable --now imagunity-sauvegarde.timer
```

Copier ensuite `/srv/sauvegardes` hors de la machine. Pour restaurer une base : `docker compose exec -T directory-db pg_restore -U annuaire -d annuaire --clean < annuaire-<date>.dump`, et de même pour `relay-db`.

## 12. Mettre à jour le site central

```bash
cd /opt/imagunity && git pull
cd deploy/site-central && docker compose build && docker compose up -d
```

Quand une version change le protocole, l'ordre est imposé : **l'annuaire, puis le relais, puis les box** (notes de version). Avec `docker compose up -d directory`, puis `relay`, on contrôle cet ordre.

## 13. Exploitation et dépannage

| Besoin | Commande |
|---|---|
| État | `docker compose ps` |
| Journaux | `docker compose logs -f relay` (de même `directory`, `caddy`) |
| Box connues du relais | journal du relais à chaque lecture de l'annuaire ; interface d'administration de l'annuaire |
| Espace des morceaux | `docker system df -v \| grep relay-data` |

| Symptôme | Cause probable |
|---|---|
| `required variable … is missing` | `.env` incomplet, ou commande lancée hors de `deploy/site-central` |
| Caddy n'obtient pas de certificat | DNS qui ne pointe pas sur la machine, ou ports 80 et 443 fermés depuis Internet : voir `docker compose logs caddy` |
| `403` sur `/admin/` depuis le poste d'administration | Son adresse n'est pas dans `RESEAU_ADMIN`. Après correction : `docker compose up -d caddy` |
| Le relais refuse une box (`401`) longtemps après sa publication | Plus de 5 minutes : vérifier l'empreinte du responsable dans `.env` et dans la box, et `docker compose logs relay` |
| Le relais refuse de démarrer | `EMPREINTE_RESPONSABLE` ne correspond pas à `config/organisation.pub.json` |
