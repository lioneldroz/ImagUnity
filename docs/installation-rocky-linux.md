# Installation sur Rocky Linux : pile d'essai complète

> Installe sur **une seule machine Rocky Linux** toute la chaîne ImagUnity de la campagne de tests :
> - le relais IU-T et l'annuaire de l'organisation ;
> - deux box, A et B ;
> - deux PACS Orthanc, A et B.
>
> Ce guide sert à une démonstration ou un essai, avec des **données exclusivement synthétiques**. Il ne décrit pas une installation de production : mots de passe d'essai, HTTP sans TLS, tout sur une machine.

```
 site A (réseau Docker)        « Internet » (réseau Docker)        site B (réseau Docker)
┌───────────────┐   ┌──────────────────────────────────────┐   ┌───────────────┐
│ Orthanc A     │   │ relais IU-T (+ PostgreSQL)           │   │ Orthanc B     │
│     │         │   │ annuaire (+ PostgreSQL)              │   │     ▲         │
│     ▼         │   │                                      │   │     │         │
│ box A ────────┼───┼──► connexions sortantes seulement ◄──┼───┼──── box B     │
└───────────────┘   └──────────────────────────────────────┘   └───────────────┘
```

Toutes les commandes sont à lancer **en root** sur la machine Rocky (ici `192.168.1.78`), sauf celles du § 9, qui se lancent depuis ton poste.

La procédure a été déroulée de bout en bout avec les mêmes fichiers Compose : enrôlement, appairage, puis envoi d'une étude de 100 images, arrivées toutes identiques en 10,6 s. Seules les commandes propres à Rocky (§ 1 et 2) n'ont pas pu être essayées sur une vraie machine Rocky.

## 0. Prérequis

| | Minimum conseillé | Remarque |
|---|---|---|
| Système | Rocky Linux 9 ou 10, x86_64 | Installation minimale suffisante |
| Processeur | 4 vCPU | La construction des images est la phase la plus longue |
| Mémoire | 8 Go | La pile tourne avec environ 1,6 Go ; la construction Maven en demande davantage |
| Disque | 40 Go libres | Images : environ 4 Go, plus le cache de construction et les études d'essai |
| Réseau | Accès à Internet | Dépôt Docker, Docker Hub, Maven Central, GitHub |
| Heure | Synchronisée (chrony) | Les requêtes signées sont refusées au-delà de 5 minutes d'écart |

Aucun port n'est à ouvrir dans le pare-feu. Les services ne sont publiés que sur `127.0.0.1` de la machine, et on y accède depuis son poste par un tunnel SSH (§ 9).

## 1. Préparer le système

```bash
dnf -y update
dnf -y install git dnf-plugins-core
# Python 3.10 ou plus récent pour le script d'essai (§ 10) : python3.11 sur Rocky 9, python3 (3.12) sur Rocky 10
dnf -y install python3.11 || dnf -y install python3
systemctl enable --now chronyd
chronyc tracking | grep -E "Leap status|System time"    # « Normal », écart de l'ordre de la milliseconde
```

SELinux peut rester en mode `enforcing`. Avec sa configuration par défaut, Docker CE n'applique pas d'étiquettes SELinux aux conteneurs (`"selinux-enabled": false`), donc les fichiers montés depuis `deploy/annuaire/` restent lisibles. Si tu as activé SELinux dans `/etc/docker/daemon.json`, voir le § 11.

## 2. Installer Docker CE

Rocky fournit Podman, qui entre en conflit avec Docker CE : on le retire d'abord, puis on installe Docker depuis son dépôt officiel pour RHEL.

```bash
dnf -y remove podman buildah runc podman-docker 2>/dev/null || true
dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
dnf -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker version --format '{{.Server.Version}}'
docker compose version          # 2.33 ou plus récent (paramètre gw_priority des fichiers Compose)
docker run --rm hello-world     # vérifie le démon et l'accès à Docker Hub
```

## 3. Récupérer ImagUnity

```bash
git clone https://github.com/lioneldroz/ImagUnity.git /opt/imagunity
cd /opt/imagunity/deploy
```

Si le dépôt est privé, utilise un jeton d'accès GitHub en lecture seule (`git clone https://<jeton>@github.com/…`) ou une clé de déploiement SSH. Ne laisse pas le jeton dans l'historique du shell.

## 4. Créer les phrases de passe des box

Chaque box chiffre ses clés avec une phrase de passe, fournie par un secret Docker. Ces fichiers ne sont jamais versionnés : le dossier `deploy/annuaire/` est ignoré par Git.

```bash
cd /opt/imagunity/deploy
mkdir -p annuaire && chmod 700 annuaire
for b in a b; do head -c 24 /dev/urandom | base64 > annuaire/phrase-box-$b.txt; done
chmod 600 annuaire/phrase-box-*.txt
```

## 5. Construire les images

Les images du relais, de l'annuaire et des box sont construites à partir des sources (Maven dans un conteneur, rien à installer sur la machine). Compte de 15 à 30 minutes la première fois.

L'empreinte du responsable n'existe pas encore : une valeur provisoire suffit pour la construction.

```bash
cd /opt/imagunity/deploy
IMAGUNITY_DIRECTORY_ORGANIZATION_CODE=provisoire docker compose build
docker images | grep imagunity     # imagunity-box-a, imagunity-box-b, imagunity-relay, imagunity-directory
```

## 6. Créer la clé du responsable de l'organisation

Le responsable signe les entrées d'annuaire des box : c'est la racine de confiance de l'organisation. L'outil du responsable (`DirectoryAdmin`) se lance depuis l'image de l'annuaire, sans installer Java sur la machine.

On définit la commande `admin`, à reprendre dans chaque nouveau shell :

```bash
mkdir -p /root/imagunity-responsable && chmod 700 /root/imagunity-responsable
cd /root/imagunity-responsable
admin() {
  docker run --rm -i --network host -v "$PWD":/travail -w /travail -e LANG=C.UTF-8 \
    -e IMAGUNITY_PHRASE_ORGANISATION -e IMAGUNITY_ANNUAIRE_COMPTE \
    --entrypoint java imagunity-directory \
    -cp /app/imagunity-directory.jar -Dloader.main=com.imagunity.directory.DirectoryAdmin \
    org.springframework.boot.loader.launch.PropertiesLauncher "$@"
}
```

La phrase de passe est passée par une variable d'environnement (au moins 16 caractères), lue sans écho pour qu'elle ne s'affiche pas et ne reste pas dans l'historique :

```bash
read -rs -p "Phrase de passe du responsable : " IMAGUNITY_PHRASE_ORGANISATION; echo
export IMAGUNITY_PHRASE_ORGANISATION
admin organisation-creer --organisation chu-essai --cles organisation.json
```

L'outil affiche ceci :

```
Clé du responsable de « chu-essai » créée : organisation.json (à garder hors ligne, protégée par phrase de passe)
Clé publique, à installer dans les box : organisation.json.pub.json
Empreinte à communiquer hors bande : XXXX-XXXX-XXXX-XXXX-XXXX-XXXX
```

Note l'empreinte, puis installe la clé publique pour la pile :

```bash
cp /root/imagunity-responsable/organisation.json.pub.json /opt/imagunity/deploy/annuaire/organisation.pub.json
```

`organisation.json` est la clé privée du responsable. En dehors d'un essai, elle se garde hors ligne, jamais sur la machine du relais.

## 7. Démarrer la pile

Le fichier `deploy/.env` (ignoré par Git) donne à Compose l'empreinte du responsable. Les box et le relais refusent de démarrer sans elle. Pour un essai, on raccourcit aussi les intervalles de lecture de l'annuaire, sinon l'appairage prend plusieurs minutes.

```bash
cd /opt/imagunity/deploy
cat > .env <<'EOF'
IMAGUNITY_DIRECTORY_ORGANIZATION_CODE=XXXX-XXXX-XXXX-XXXX-XXXX-XXXX
IMAGUNITY_DIRECTORY_SYNC_INTERVAL=10s
IMAGUNITY_RELAY_DIRECTORY_SYNC_INTERVAL=10s
EOF
chmod 600 .env
docker compose up -d
docker compose ps
```

Les deux box sont prêtes quand elles répondent :

```bash
H='-H X-ImagUnity-Console:1 -u operateur:essai-synthetique'
until curl -sf $H localhost:8081/api/v1/box && curl -sf $H localhost:8082/api/v1/box; do sleep 3; done; echo
```

## 8. Enrôler les box, puis les appairer

### 8.1 Enrôlement

Au premier démarrage, chaque box crée son identité et prépare une **demande d'enrôlement**. Le responsable la signe, puis publie l'entrée dans l'annuaire.

```bash
cd /root/imagunity-responsable
H='-H X-ImagUnity-Console:1 -u operateur:essai-synthetique'
curl -sf $H localhost:8081/api/v1/directory/enrollment-request -o demande-box-a.json
curl -sf $H localhost:8082/api/v1/directory/enrollment-request -o demande-box-b.json

export IMAGUNITY_ANNUAIRE_COMPTE=responsable:essai-synthetique   # compte de dépôt de la pile d'essai
for b in a b; do
  admin enroler --cles organisation.json --demande demande-box-$b.json --sortie entree-box-$b.json
  admin publier --annuaire http://127.0.0.1:8090 --entree entree-box-$b.json
done
```

Pour chaque box, l'outil affiche une ligne comme celle-ci :

```
Box « box-a » (…) enrôlée pour 90 jours, n° 1
Vérifiez hors bande l'empreinte de la box avec son installateur : XXXX-…
Entrée publiée dans l'annuaire
```

Dans un vrai déploiement, l'installateur de chaque site confirme cette empreinte de vive voix. Ici, on peut la comparer avec celle que donne la box elle-même :

```bash
curl -s $H localhost:8081/api/v1/directory | python3 -c "import json,sys; print(json.load(sys.stdin)['shortCode'])"
```

Une fois son entrée publiée et lue par le relais, chaque box crée seule sa file de réception.

### 8.2 Appairage

On demande à la box A d'appairer la box B, sous le nom `box-b` utilisé par la route DICOM. La box B crée le contact `box-a` d'elle-même, à la réception de la poignée de main.

```bash
B=$(curl -s $H localhost:8082/api/v1/directory | python3 -c "import json,sys; print(json.load(sys.stdin)['boxId'])")
# Attendre que la box A voie la box B active dans l'annuaire, avec son adresse :
until curl -s $H localhost:8081/api/v1/directory | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if any(b['boxId']=='$B' and b['reachable'] for b in d['boxes']) else 1)"; do sleep 3; done
curl -s $H -H 'Content-Type: application/json' -X POST localhost:8081/api/v1/directory/boxes/$B/pair -d '{"name":"box-b"}' -w 'HTTP %{http_code}\n'   # 202
# Contrôle : chaque box connaît l'autre, à l'état ACTIVE.
curl -s $H localhost:8081/api/v1/peers; echo
curl -s $H localhost:8082/api/v1/peers; echo
```

La seconde liste peut rester vide quelques dizaines de secondes, le temps que la box B lise l'annuaire.

## 9. Accéder aux interfaces depuis ton poste

Les services n'écoutent que sur `127.0.0.1` de la machine Rocky. Depuis ton poste, ouvre un tunnel SSH et garde la session ouverte :

```bash
ssh -N -L 8042:127.0.0.1:8042 -L 8043:127.0.0.1:8043 \
       -L 8081:127.0.0.1:8081 -L 8082:127.0.0.1:8082 \
       -L 8090:127.0.0.1:8090 root@192.168.1.78
```

| Service | Adresse sur ton poste | Compte |
|---|---|---|
| Orthanc A, PACS émetteur | http://localhost:8042 | aucun |
| Orthanc B, PACS destinataire | http://localhost:8043 | aucun |
| API de la box A | http://localhost:8081/api/v1/overview | `operateur` / `essai-synthetique`, en-tête `X-ImagUnity-Console: 1` pour les requêtes qui modifient l'état |
| API de la box B | http://localhost:8082/api/v1/overview | idem |
| Annuaire : interface d'administration | http://localhost:8090/admin/ | `responsable` / `essai-synthetique` |

Les box n'exposent que leur API. La console React (`frontend/`) se lance à part, en développement (`npm run dev`), et n'est pas incluse dans cette pile.

## 10. Faire un envoi d'essai

### 10.1 Par le script de la campagne (recommandé)

Le script crée une étude CT **synthétique** : patient « SYNTHETIQUE^CAMPAGNE », 100 images, environ 50 Mo. Il la dépose dans Orthanc A, l'envoie à la box B, suit le transfert, puis compare les images arrivées dans Orthanc B à celles envoyées, octet par octet.

```bash
cd /opt/imagunity/deploy
PY=$(command -v python3.11 || command -v python3)
$PY -m venv campagne/.venv
campagne/.venv/bin/pip install -r campagne/requirements.txt
campagne/.venv/bin/python campagne/campagne.py nominal
```

Résultat attendu, sur la dernière ligne :

```
[nominal] {"scenario": "nominal", "instances": 100, … "etatFinal": "ACKNOWLEDGED", … "verification": {"instancesA": 100, "instancesB": 100, "identiques": 100}}
```

Les résultats sont écrits dans `deploy/campagne/resultats/`. Ne lance pas les autres scénarios sur cette pile : ils coupent le réseau, éteignent des conteneurs et supposent les images préparées de la campagne ([phase 8](phase8-campagne.md)).

### 10.2 À la main, depuis Orthanc A

1. Ouvrir http://localhost:8042 par le tunnel, puis déposer une étude **synthétique** (fichiers `.dcm`) par « Upload ».
2. Sur l'étude, « Send to DICOM modality », puis `imagunity-b`.
3. Suivre le transfert : `curl -s -u operateur:essai-synthetique localhost:8081/api/v1/transfers`. Il est terminé à l'état `ACKNOWLEDGED`.
4. L'étude apparaît dans Orthanc B (http://localhost:8043).

Le sens inverse marche aussi : depuis Orthanc B, vers `imagunity-a`.

## 11. Exploitation et dépannage

| Besoin | Commande |
|---|---|
| État des conteneurs | `cd /opt/imagunity/deploy && docker compose ps` |
| Journaux d'une box | `docker compose logs -f box-a` (de même `box-b`, `relay`, `directory`) |
| Arrêter, relancer | `docker compose stop`, `docker compose start` ; les données et l'enrôlement sont gardés |
| Après un redémarrage de la machine | rien à faire : `docker compose up -d` si des conteneurs sont arrêtés |
| Mettre à jour | `git pull && docker compose build && docker compose up -d` |
| Tout effacer et recommencer | `docker compose down -v`, puis reprendre au § 7 (identités des box et annuaire perdus : nouvel enrôlement) |

| Symptôme | Cause probable et remède |
|---|---|
| `required variable IMAGUNITY_DIRECTORY_ORGANIZATION_CODE is missing` | `deploy/.env` absent ou commande lancée hors de `deploy/` (§ 7) |
| `gw_priority` refusé par Compose | Docker Compose trop ancien : mettre à jour `docker-compose-plugin` (§ 2) |
| Une box s'arrête : empreinte du responsable différente | L'empreinte de `.env` ne correspond pas à `annuaire/organisation.pub.json` (§ 6 et 7) |
| Construction en échec pendant le téléchargement Maven | Accès à Internet, ou proxy d'entreprise à déclarer à Docker (`/etc/systemd/system/docker.service.d/proxy.conf`) |
| `Requête refusée : horodatage hors de la fenêtre admise` | Horloge de la machine décalée : `chronyc makestep`, vérifier `chronyd` (§ 1) |
| L'appairage est refusé, ou la box B n'est pas `reachable` | Entrée pas encore lue : attendre un cycle (10 s avec le `.env` du § 7), vérifier `docker compose logs relay` |
| `Permission denied` sur `/config/organisation.pub.json` | SELinux activé pour Docker : ajouter `:z` aux montages de `annuaire/`, ou `chcon -Rt container_file_t /opt/imagunity/deploy/annuaire` |
| Le tunnel SSH ne s'ouvre pas | `AllowTcpForwarding` désactivé dans `/etc/ssh/sshd_config` |

## 12. Sécurité de cette installation

- **Essai seulement.** Les mots de passe `essai-synthetique` de `docker-compose.yml` sont publics. Les liaisons sont en HTTP, sans TLS. Aucune donnée réelle de patient.
- **Accès** : rien n'est publié sur le réseau, tout passe par SSH. Utiliser une connexion par clé SSH plutôt que le mot de passe root, et changer ce mot de passe s'il a circulé.
- **Clé du responsable** : `/root/imagunity-responsable/organisation.json`, avec sa phrase de passe. Elle suffit pour enrôler une box de l'organisation : ne la copier nulle part ailleurs.
- **Pour aller plus loin** : la configuration réelle d'une box, du relais et de l'annuaire est décrite dans [deploy/README.md](../deploy/README.md). Le relais réparti sur K3s est décrit dans [deploy/k3s/README.md](../deploy/k3s/README.md). Les limites connues avant l'audit externe sont dans le [pré-audit](audit/pre-audit.md).
