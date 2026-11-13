# Installation d'une box sur Rocky Linux 10 (systemd)

> La **box** est le petit serveur placé dans chaque établissement :
> - elle reçoit les examens du PACS (DICOM, ou DICOMweb) ;
> - elle les chiffre pour le destinataire et les dépose sur le relais du site central ;
> - en sens inverse, elle restitue au PACS les examens reçus.
>
> Elle s'installe **sans conteneur**, sous systemd, et **se met à jour seule** avec des versions signées par l'éditeur. Le site central (relais, annuaire, serveur de mises à jour) a son propre guide : [installation du site central](installation-site-central-rocky10.md).
>
> Niveau **pré-production**, avec des **données exclusivement synthétiques** tant que l'[audit de sécurité](audit/dossier-audit.md) n'est pas fait.

```
 réseau de l'établissement                                     Internet / organisation
┌────────────────────────────────────────────┐
│ PACS ── DICOM (11112) ──► box ─────────────┼── HTTPS sortant ──► relais.<domaine>
│      ◄── DICOM (C-STORE) ──┘ │  │          │                     annuaire.<domaine>
│ poste d'exploitation ── API (8080) ──┘     │                     (dont /mises-a-jour/)
└────────────────────────────────────────────┘
```

Aucune connexion n'entre depuis Internet : la box ne fait que des connexions sortantes.

## Comment la box est protégée sur la machine

| Élément | Emplacement | Propriétaire |
|---|---|---|
| Lanceur, version livrée, scripts | `/opt/imagunity/` | root, lecture seule pour la box |
| Versions installées par les mises à jour, et état anti-retour | `/var/lib/imagunity-maj/` | root |
| Configuration, clés publiques, magasin de confiance | `/etc/imagunity/` | root, groupe `imagunity` en lecture |
| Phrase de passe des clés de la box | `/etc/imagunity/phrase-box` | root seul (0600) ; transmise au service par systemd |
| Données de la box : identité, base, transferts | `/var/lib/imagunity-box/` | compte `imagunity`, 0700 |

Le service `imagunity-box` se déroule en deux temps.
1. Le **lanceur**, en root, vérifie la signature de l'éditeur sur chaque version disponible et refuse tout retour en arrière.
2. Le **logiciel** démarre ensuite sous le compte `imagunity`, sans aucun privilège. Le système de fichiers est en lecture seule pour lui, sauf ses propres données.

Une faille dans le logiciel ne permet donc ni de modifier le lanceur, ni de remplacer une version, ni de lire la phrase de passe. C'est la réponse au constat D3 du [pré-audit](audit/pre-audit.md).

**Ce qui a été essayé.** Les mêmes scripts et services ont été essayés en conditions réelles :
- installation par `installer.sh` ;
- démarrage avec l'enchaînement de systemd : lanceur en root, puis logiciel sous `imagunity` ;
- connexion au site central en HTTPS, par une autorité interne ;
- enrôlement, appairage, puis transfert de 100 images arrivées identiques ;
- mise à jour automatique 0.14.0 → 0.14.1 par le serveur de mises à jour, avec contrôle de santé ;
- contrôle que le compte `imagunity` ne peut écrire ni dans les versions, ni dans le lanceur, ni lire la phrase de passe.

L'environnement d'essai n'avait pas systemd comme premier processus : son enchaînement a été reproduit à l'identique, et les fichiers de service validés par `systemd-analyze verify`. Restent à essayer sur une vraie machine Rocky Linux 10 : systemd lui-même, avec ses protections (`ProtectSystem`, `LoadCredential`…), l'installation de Java (§ 2) et le TPM.

## 0. Prérequis

| | Conseillé | Remarque |
|---|---|---|
| Système | Rocky Linux 10, x86_64, installation minimale | Machine dédiée |
| Processeur, mémoire | 2 vCPU, 4 Go | La box utilise environ 450 Mo en marche |
| Disque | 100 Go et plus pour `/var/lib/imagunity-box` | Au moins 3 fois le plus gros examen attendu : réception, paquets, restitution |
| Réseau | Adresse IP fixe sur le réseau de l'établissement ; HTTPS sortant (443) vers les deux noms du site central | Pas de port entrant depuis Internet |
| TPM 2.0 | Recommandé | Protège les clés de la box au repos (§ 7) |
| Heure | Synchronisée (chrony) | Requêtes signées refusées au-delà de 5 minutes d'écart |

À obtenir du site central avant de commencer :
- la **clé publique du responsable** (`organisation.pub.json`) et son **empreinte**, dictée de vive voix ;
- la **clé publique de l'éditeur** (`editeur.pub.json`) et son empreinte ;
- l'**archive de la box** (`imagunity-box-<version>.tar.gz`) ;
- les noms `relais.<domaine>` et `annuaire.<domaine>` ;
- si le site central utilise une autorité interne : son **certificat racine** et son empreinte.

## 1. Préparer le système

```bash
dnf -y update
dnf -y install tar curl httpd-tools
systemctl enable --now chronyd
```

`httpd-tools` fournit `htpasswd`, qui sert à hacher le mot de passe de l'opérateur (§ 6).

## 2. Installer Java 25

Voir d'abord si Rocky le fournit :

```bash
dnf list --available 'java-25-openjdk*'
dnf -y install java-25-openjdk-headless        # si la liste précédente le propose
```

Sinon, utiliser le dépôt d'Adoptium (Temurin) :

```bash
cat > /etc/yum.repos.d/adoptium.repo <<'EOF'
[Adoptium]
name=Adoptium
baseurl=https://packages.adoptium.net/artifactory/rpm/rhel/$releasever/$basearch
enabled=1
gpgcheck=1
gpgkey=https://packages.adoptium.net/artifactory/api/gpg/key/public
EOF
dnf -y install temurin-25-jre
```

Dans les deux cas, vérifier avec `java -version`, qui doit afficher `version "25…"`. Java se met ensuite à jour avec le système (§ 10). Aucune de ces deux sources n'a pu être essayée depuis l'environnement d'essai.

## 3. Installer la box

```bash
cd /root
tar -xzf imagunity-box-0.14.0.tar.gz && cd imagunity-box-0.14.0
# Vérifier l'archive avec la clé de l'éditeur, avant toute installation :
java -jar imagunity-release.jar empreinte --cle-publique /chemin/editeur.pub.json      # = empreinte dictée ?
java -jar imagunity-release.jar verifier --cle-publique /chemin/editeur.pub.json \
    --manifeste imagunity-box.jar.manifeste.json --artefact imagunity-box.jar
sh installer.sh
```

`installer.sh` effectue les opérations suivantes, puis rend la main sans rien démarrer :
1. il crée le compte de service `imagunity` ;
2. il installe le lanceur et la version livrée dans `/opt/imagunity` ;
3. il installe les services systemd ;
4. il crée `/etc/imagunity/box.env` à partir de l'exemple, et la phrase de passe `/etc/imagunity/phrase-box`.

Relancé plus tard avec une nouvelle archive, il remplace le lanceur et les scripts sans toucher à la configuration ni aux données.

## 4. Installer les clés publiques

```bash
install -m 0644 /chemin/organisation.pub.json /etc/imagunity/organisation.pub.json
install -m 0644 /chemin/editeur.pub.json      /etc/imagunity/editeur.pub.json
# Empreinte du responsable (à comparer avec celle dictée) :
java -cp /opt/imagunity/imagunity-box.jar -Dloader.main=com.imagunity.directory.DirectoryAdmin \
    org.springframework.boot.loader.launch.PropertiesLauncher empreinte --cle-publique /etc/imagunity/organisation.pub.json
```

## 5. Faire confiance à l'autorité interne du site central (si besoin)

À faire seulement si le site central n'a pas de certificat public (§ 7 B ou C de son guide).

```bash
openssl x509 -noout -fingerprint -sha256 -in autorite-interne.crt      # = empreinte dictée ?
keytool -importcert -noprompt -storetype PKCS12 -keystore /etc/imagunity/confiance.p12 \
    -storepass changeit -alias imagunity-central -file autorite-interne.crt
chmod 0644 /etc/imagunity/confiance.p12
```

Ensuite, dans `box.env`, décommenter la ligne `IMAGUNITY_JAVA_OPTS=-Djavax.net.ssl.trustStore=/etc/imagunity/confiance.p12 …`.

La box ne fait alors confiance qu'à cette autorité, ce qui épingle de fait le site central. Le mot de passe `changeit` ne protège rien : ce magasin ne contient qu'un certificat public.

## 6. Configurer la box

Tout se règle dans `/etc/imagunity/box.env`, au format systemd : une variable par ligne, `NOM=valeur`, une valeur contenant `$` entre apostrophes. L'exemple installé est commenté ligne par ligne. L'essentiel :

| Variable | Rôle | Exemple |
|---|---|---|
| `IMAGUNITY_BOX_NAME` | Nom de la box, vu des autres sites | `box-chu-nord` |
| `SERVER_ADDRESS`, `IMAGUNITY_DICOM_BIND_ADDRESS` | Adresse IP de la box sur le réseau de l'établissement. Rien n'écoute ailleurs | `192.168.1.78` |
| `IMAGUNITY_HTTP_PORT`, `IMAGUNITY_DICOM_PORT` | Ports de l'API et de l'entrée DICOM | `8080`, `11112` |
| `IMAGUNITY_CONSOLE_PASSWORD` | Mot de passe de l'opérateur, haché (voir ci-dessous) | `'{bcrypt}$2y$10$…'` |
| `IMAGUNITY_DICOM_ALLOWED_CALLING_AETS` | AET des PACS autorisés à envoyer | `PACS_CHU` |
| `IMAGUNITY_DICOM_LIMITS_ALLOWED_SOURCES` | Adresses ou réseaux autorisés à ouvrir une association DICOM | `192.168.1.10` |
| `IMAGUNITY_DICOM_ROUTES_0_CALLED_AET`, `…_DESTINATION` | Destination DICOM à déclarer dans le PACS, et partenaire désigné | `IU_TO_B`, `box-b` |
| `IMAGUNITY_PACS_HOST`, `…_PORT`, `…_AET`, `IMAGUNITY_BOX_AET` | Restitution des examens reçus au PACS | `192.168.1.10`, `104`, `PACS_CHU`, `IMAGUNITY` |
| `IMAGUNITY_DIRECTORY_URL`, `IMAGUNITY_IU_RELAY` | Site central | `https://annuaire.exemple.org`, `https://relais.exemple.org` |
| `IMAGUNITY_DIRECTORY_ORGANIZATION_CODE` | Empreinte du responsable | `XXXX-…` |
| `IMAGUNITY_RELEASE_CODE` | Empreinte de l'éditeur | `XXXX-…` |
| `IMAGUNITY_UPDATE_URL` | Canal de mises à jour : `pilote` ou `general` | `https://annuaire.exemple.org/mises-a-jour/general/` |

Les autres réglages sont décrits dans les guides par sujet :
- routage par champ DICOM : [routage DICOM](routage-dicom.md) ;
- réception par DICOMweb : [STOW-RS](stow-rs.md) ;
- ordre d'envoi des séries : [priorité des séries](priorite-dicom.md) ;
- limites de l'entrée DICOM : [phase 2](phase2-dicom.md).

**Mot de passe de l'opérateur.** `htpasswd` le demande deux fois, sans qu'il passe sur la ligne de commande :

```bash
htpasswd -nBC 10 "" | tr -d ':\n'; echo        # → $2y$10$…
# dans box.env :  IMAGUNITY_CONSOLE_PASSWORD='{bcrypt}$2y$10$…'   (entre apostrophes)
```

**Pare-feu** : n'ouvrir l'entrée DICOM qu'au PACS, et l'API qu'au poste d'exploitation.

```bash
firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=192.168.1.10/32 port port=11112 protocol=tcp accept'
firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=192.168.1.20/32 port port=8080 protocol=tcp accept'
firewall-cmd --reload
```

## 7. Protéger les clés par le TPM (recommandé)

```bash
dnf -y install tpm2-tools
install -D -m 0644 /opt/imagunity/imagunity-box-tpm.conf /etc/systemd/system/imagunity-box.service.d/tpm.conf
systemctl daemon-reload
```

Dans `box.env`, mettre `IMAGUNITY_DIRECTORY_IDENTITY_TPM=true` et `IMAGUNITY_DIRECTORY_IDENTITY_TPM_PCRS=sha256:0,4,7,8,9`.

Au premier démarrage, le secret qui protège les clés de la box est scellé dans le TPM, lié à l'état de démarrage de la machine. Une mise à jour du micrologiciel, du chargeur ou du noyau change ces registres : la box ne peut plus desceller ses clés, et il faut alors un nouvel enrôlement. Les précautions sont détaillées dans [clés et TPM](cles-tpm.md). La box avertit au démarrage si le lien aux PCR est trop faible.

## 8. Démarrer

```bash
systemctl enable --now imagunity-box.service
journalctl -u imagunity-box -f          # « Démarrage sur la version … signature vérifiée », puis « Started ImagUnityApplication »
curl -s http://192.168.1.78:8080/actuator/health      # {"status":"UP",…}
```

Tant que la box n'est pas enrôlée, son journal affiche des refus `401` du relais : c'est normal.

## 9. Enrôler, puis appairer

**Enrôlement.** On télécharge la demande d'enrôlement de la box, et on relève son empreinte :

```bash
U='-u operateur:<mot de passe>'
curl -sf $U http://192.168.1.78:8080/api/v1/directory/enrollment-request -o demande-box-chu-nord.json
curl -s  $U http://192.168.1.78:8080/api/v1/directory | python3 -c "import json,sys; print(json.load(sys.stdin)['shortCode'])"
```

On envoie le fichier au responsable, et on lui dicte l'empreinte quand il la demande. Il signe et publie l'entrée ([guide du site central, § 8](installation-site-central-rocky10.md)). Dans les 5 minutes qui suivent, le journal de la box affiche « File de réception IU-T créée ».

**Appairage** avec chaque partenaire, sous le nom utilisé par la route DICOM. L'identifiant `boxId` du partenaire se lit dans `GET /api/v1/directory`, liste `boxes` :

```bash
curl -s $U -H 'X-ImagUnity-Console: 1' -H 'Content-Type: application/json' \
    -X POST http://192.168.1.78:8080/api/v1/directory/boxes/<boxId du partenaire>/pair -d '{"name":"box-b"}'
curl -s $U http://192.168.1.78:8080/api/v1/peers       # partenaire « ACTIVE »
```

**Côté PACS** :
- déclarer la destination DICOM `IU_TO_B`, à l'adresse et au port de la box ;
- autoriser l'AET `IMAGUNITY_BOX_AET` à y déposer, pour la restitution des examens reçus.

## 10. Mises à jour automatiques

Trois couches se mettent à jour, par trois voies :

| Couche | Comment | Quand |
|---|---|---|
| Logiciel ImagUnity | Versions signées par l'éditeur, téléchargées depuis `IMAGUNITY_UPDATE_URL` | Chaque nuit, entre 3 h et 4 h |
| Java et système Rocky | `dnf-automatic` : dépôts signés de Rocky (et d'Adoptium) | Selon son réglage |
| Lanceur et scripts de la box | Nouvelle archive et `installer.sh` | Rarement ; annoncé par l'éditeur |

**Logiciel ImagUnity :**

```bash
systemctl enable --now imagunity-box-maj.timer
systemctl list-timers imagunity-box-maj.timer
```

Chaque nuit, `imagunity-box-maj.service` télécharge et vérifie la dernière version publiée, sans l'exécuter. S'il y en a une plus récente, il redémarre la box. Au démarrage, la box la revérifie et la retient comme dernière exécutée, puis le service attend qu'elle réponde jusqu'à 5 minutes. Un transfert en cours reprend après le redémarrage, au morceau près.

| Besoin | Commande |
|---|---|
| Changer la fenêtre de maintenance | `systemctl edit imagunity-box-maj.timer`, puis écrire sous `[Timer]` une ligne `OnCalendar=` vide (efface la valeur par défaut), suivie de `OnCalendar=Sun *-*-* 04:00` |
| Vérifier tout de suite | `systemctl start imagunity-box-maj.service && journalctl -u imagunity-box-maj -n 20` |
| Changer de canal (pilote ou général) | `IMAGUNITY_UPDATE_URL` dans `box.env` ; pris en compte à la vérification suivante |
| Box sans accès au serveur | Copier `<jar>` et `<jar>.manifeste.json` dans `/var/lib/imagunity-maj/depot/`, puis lancer la vérification immédiate. Une pièce refusée part dans `depot/refusees/` |
| Version installée | `curl -s $U http://192.168.1.78:8080/api/v1/box` |

**Alertes.** En cas d'échec de la box ou d'une mise à jour, par exemple si la box ne répond plus après une mise à jour, systemd lance `/etc/imagunity/alerte <nom du service>`, s'il existe et est exécutable :

```bash
cat > /etc/imagunity/alerte <<'EOF'
#!/bin/sh
# Exemple : message dans le journal système ; à remplacer par l'envoi d'un courriel ou une alerte de supervision.
logger -p daemon.err -t imagunity "Échec de $1 sur $(hostname) : voir journalctl -u $1"
EOF
chmod 0750 /etc/imagunity/alerte
```

Une version défectueuse ne se retire pas, puisque le retour en arrière est refusé. L'éditeur publie une version corrigée de numéro supérieur, d'abord sur le canal `pilote`.

**Java et système :**

```bash
dnf -y install dnf-automatic
sed -i 's/^apply_updates.*/apply_updates = yes/' /etc/dnf/automatic.conf
systemctl enable --now dnf-automatic.timer
```

Une mise à jour de Java ne prend effet qu'au redémarrage de la box. La mise à jour nocturne la redémarre quand une version ImagUnity arrive ; sinon, prévoir `systemctl restart imagunity-box` après les mises à jour du système.

## 11. Exploitation et dépannage

| Besoin | Commande |
|---|---|
| État | `systemctl status imagunity-box imagunity-box-maj.timer` |
| Journal | `journalctl -u imagunity-box -f` ; mises à jour : `journalctl -u imagunity-box-maj` |
| Transferts | `curl -s $U http://<ip>:8080/api/v1/overview` et `…/api/v1/transfers` |
| Sauvegarde | `/var/lib/imagunity-box` (identité de la box, transferts) et `/etc/imagunity`. Sans elles, il faut une nouvelle identité et un nouvel enrôlement |
| Désinstaller | `systemctl disable --now imagunity-box imagunity-box-maj.timer`, puis faire révoquer l'entrée de la box par le responsable |

| Symptôme | Cause probable |
|---|---|
| `Erreur : empreinte de l'éditeur non confirmée` au démarrage | `IMAGUNITY_RELEASE_CODE` différent de l'empreinte de `editeur.pub.json` |
| `Retour en arrière refusé` | Une version plus ancienne que la dernière exécutée a été remise en place : attendre une version plus récente |
| Refus `401` du relais au-delà de 5 minutes après la publication | Entrée pas publiée, ou empreinte du responsable différente dans `box.env` |
| `PKIX path building failed` dans le journal | Le site central a une autorité interne non importée (§ 5), ou `IMAGUNITY_JAVA_OPTS` n'est pas décommentée |
| Le PACS est refusé à l'association | AET absent de `IMAGUNITY_DICOM_ALLOWED_CALLING_AETS`, adresse hors de `…_ALLOWED_SOURCES`, ou pare-feu |
| `Le TPM refuse de desceller…` | L'état de démarrage a changé (micrologiciel, noyau) : voir [clés et TPM](cles-tpm.md) |
| La box ne démarre pas après une mise à jour | `journalctl -u imagunity-box -b`. L'alerte a été déclenchée ; l'éditeur publie une version corrigée |

## Annexe : préparer l'archive (éditeur)

```bash
cd backend && ./mvnw -DskipTests package && cd ..
java -jar backend/release/target/imagunity-release.jar signer --cles editeur.json \
    --artefact backend/app/target/imagunity-box.jar --version 0.15.0 --sequence 28
sh deploy/box-systemd/construire-archive.sh 0.15.0          # → imagunity-box-0.15.0.tar.gz
```

L'archive contient :
- le lanceur (`imagunity-release.jar`) ;
- la version signée de la box et son manifeste ;
- les scripts, les services systemd, l'installateur et l'exemple de configuration.

La même version signée se publie sur le serveur de mises à jour ([guide du site central, § 9](installation-site-central-rocky10.md)).
