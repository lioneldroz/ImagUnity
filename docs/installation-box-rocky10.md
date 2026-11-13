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

**Aucun processus de la box ne tourne en root.** Deux comptes de service, sans mot de passe ni shell, se partagent le travail :

| Compte | Rôle | Unités systemd |
|---|---|---|
| `imagunity` | Exécute la box : DICOM, transferts, API | `imagunity-box.service` |
| `imagunity-maj` | Vérifie la signature de l'éditeur avant chaque démarrage et choisit la version ; chaque nuit, télécharge les mises à jour et redémarre la box | `imagunity-box-choix.service` (lancé par la box à chaque démarrage), `imagunity-box-maj.service` et son timer |

`imagunity-maj` ne peut redémarrer que la box, et rien d'autre. Ce droit lui est accordé par une règle polkit (`/etc/polkit-1/rules.d/50-imagunity-maj.rules`).

| Élément | Emplacement | Propriétaire | Accès |
|---|---|---|---|
| Lanceur, version livrée, scripts | `/opt/imagunity/` | root | Lecture seule pour les deux comptes ; ne change qu'avec `installer.sh` |
| Versions installées, état anti-retour, dépôt manuel | `/var/lib/imagunity-maj/` | `imagunity-maj` | Lecture seule pour la box |
| Version choisie au démarrage | `/run/imagunity-choix/choix` | `imagunity-maj` | Lecture seule pour la box |
| Configuration (`box.env`) et phrase de passe (`phrase-box`) | `/etc/imagunity/` | root, 0600 | Lues par systemd seul, qui les transmet aux services ; aucun des deux comptes ne peut les lire |
| Clés publiques, magasin de confiance | `/etc/imagunity/` | root, 0644 | Lecture pour les deux comptes |
| Données de la box : identité, base, transferts | `/var/lib/imagunity-box/` | `imagunity`, 0700 | Inaccessibles à `imagunity-maj` |

Une faille dans le logiciel de la box ne permet donc pas :
- de modifier le lanceur ;
- de remplacer ou d'ajouter une version ;
- de revenir à une version plus ancienne ;
- de lire la phrase de passe.

C'est la réponse au constat D3 du [pré-audit](audit/pre-audit.md). De son côté, le compte des mises à jour ne voit ni les données de la box ni ses clés, et ne peut lui-même modifier que les versions, toujours vérifiées au démarrage.

**Ce qui a été essayé.** Les mêmes scripts et services ont été essayés en conditions réelles :
- installation par `installer.sh`, avec ses deux comptes ;
- démarrage avec l'enchaînement de systemd : choix de la version sous `imagunity-maj`, puis logiciel sous `imagunity`, aucun processus root ;
- connexion au site central en HTTPS, par une autorité interne ;
- enrôlement, appairage, puis transfert de 100 images arrivées identiques ;
- mises à jour automatiques sous `imagunity-maj`, par le serveur de mises à jour et par le dépôt manuel (0.14.0 → 0.14.1 → 0.14.2 → 0.14.3), avec redémarrage et contrôle de la version retenue ;
- droits : chaque compte est refusé hors de son rôle (versions, lanceur, choix, données de l'autre, `box.env`, phrase de passe).

L'environnement d'essai n'avait pas systemd comme premier processus. Son enchaînement a été reproduit à l'identique, y compris le redémarrage demandé par `imagunity-maj`, et les fichiers de service ont été validés par `systemd-analyze verify`.

La **CI** vérifie en outre, à chaque modification :
- l'installation dans un conteneur Rocky Linux 10, avec Java 25 et polkit issus des dépôts de Rocky ;
- les comptes et les droits, et la réinstallation sans perte de configuration ;
- le lanceur sous `imagunity-maj`, puis le démarrage de la box sous `imagunity` ;
- les unités systemd (`systemd-analyze verify`, complément TPM compris) et les scripts (`shellcheck`).

Restent à essayer sur une vraie machine Rocky Linux 10, avec systemd comme premier processus :
- les protections de systemd (`ProtectSystem`, `LoadCredential`…) ;
- le lancement de `imagunity-box-choix.service` à chaque redémarrage, par dépendance ;
- la règle polkit ;
- le TPM.

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
dnf -y install tar curl httpd-tools polkit
systemctl enable --now chronyd
```

- `httpd-tools` fournit `htpasswd`, qui sert à hacher le mot de passe de l'opérateur (§ 6).
- `polkit` permet au compte des mises à jour de redémarrer la box sans root. Il est souvent déjà présent ; l'installateur refuse de continuer sans lui.

## 2. Installer Java 25

Rocky Linux 10 le fournit (vérifié par la CI, version 25.0.5 LTS) :

```bash
dnf -y install java-25-openjdk-headless
```

À défaut (version mineure de Rocky qui ne l'aurait pas), le dépôt d'Adoptium (Temurin) :

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

Dans les deux cas, vérifier avec `java -version`, qui doit afficher `version "25…"`. Java se met ensuite à jour avec le système (§ 10).

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
1. il crée les comptes de service `imagunity` et `imagunity-maj` ;
2. il installe le lanceur et la version livrée dans `/opt/imagunity` ;
3. il crée `/var/lib/imagunity-maj`, avec son dossier de dépôt, au nom de `imagunity-maj` ;
4. il installe les services systemd et la règle polkit ;
5. il crée `/etc/imagunity/box.env` (0600) à partir de l'exemple, et la phrase de passe `/etc/imagunity/phrase-box` (0600).

Une installation antérieure, dont les versions appartenaient à root, est reprise : l'installateur les remet au nom de `imagunity-maj`.

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

## 9. Enrôler, puis appairer, depuis la console

La **console** de la box s'ouvre dans un navigateur du poste d'exploitation, à l'adresse `http://192.168.1.78:8080/`, avec le compte opérateur. Elle est servie par la box elle-même. On y suit en direct :
- les transferts envoyés et reçus ;
- les partenaires ;
- l'annuaire ;
- la configuration centralisée ;
- les règles de priorité.

Le compte circule en HTTP sur le réseau du site : à réserver au poste d'exploitation (pare-feu, § 6) ou à placer derrière un proxy TLS.

**Enrôlement.** Dans la carte « Annuaire de l'organisation » :
1. relever l'**empreinte** de la box ;
2. cliquer sur **« Télécharger la demande d'enrôlement »** ;
3. envoyer le fichier au responsable ;
4. lui dicter l'empreinte quand il la demande.

Le responsable signe et publie l'entrée ([guide du site central, § 8](installation-site-central-rocky10.md)). Dans les 5 minutes qui suivent, la carte affiche la box comme active, et le journal affiche « File de réception IU-T créée ».

**Appairage** avec chaque partenaire. La même carte liste les box de l'annuaire. Pour chaque partenaire :
1. saisir le nom utilisé par la route DICOM (par exemple `box-b`) ;
2. cliquer sur **« Appairer »** ;
3. vérifier que le partenaire apparaît dans la carte « Partenaires ».

Sans navigateur, les mêmes opérations se font par l'API :

```bash
U='-u operateur:<mot de passe>'
curl -sf $U http://192.168.1.78:8080/api/v1/directory/enrollment-request -o demande-box-chu-nord.json
curl -s  $U http://192.168.1.78:8080/api/v1/directory          # empreinte (shortCode), box de l'annuaire (boxes)
curl -s  $U -H 'X-ImagUnity-Console: 1' -H 'Content-Type: application/json' \
    -X POST http://192.168.1.78:8080/api/v1/directory/boxes/<boxId du partenaire>/pair -d '{"name":"box-b"}'
curl -s  $U http://192.168.1.78:8080/api/v1/peers                # partenaire « ACTIVE »
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

Chaque nuit, `imagunity-box-maj.service`, sous le compte `imagunity-maj`, télécharge et vérifie la dernière version publiée, sans l'exécuter. S'il y en a une plus récente, il redémarre la box. Au démarrage, la box la revérifie et la retient comme dernière exécutée, puis le service attend qu'elle réponde jusqu'à 5 minutes. Un transfert en cours reprend après le redémarrage, au morceau près.

| Besoin | Commande |
|---|---|
| Changer la fenêtre de maintenance | `systemctl edit imagunity-box-maj.timer`, puis écrire sous `[Timer]` une ligne `OnCalendar=` vide (efface la valeur par défaut), suivie de `OnCalendar=Sun *-*-* 04:00` |
| Vérifier tout de suite | `systemctl start imagunity-box-maj.service && journalctl -u imagunity-box-maj -n 20` |
| Changer de canal (pilote ou général) | `IMAGUNITY_UPDATE_URL` dans `box.env` ; pris en compte à la vérification suivante |
| Box sans accès au serveur | Copier `<jar>` et `<jar>.manifeste.json` dans `/var/lib/imagunity-maj/depot/`, puis lancer la vérification immédiate. Une pièce refusée part dans `depot/refusees/` |
| Version installée | `curl -s $U http://192.168.1.78:8080/api/v1/box` |

**Alertes.** En cas d'échec de la box ou d'une mise à jour, par exemple si la box ne répond plus après une mise à jour, systemd lance `/etc/imagunity/alerte <nom du service>` sous le compte `imagunity-maj`, s'il existe et est exécutable :

```bash
cat > /etc/imagunity/alerte <<'EOF'
#!/bin/sh
# Exemple : message dans le journal système ; à remplacer par l'envoi d'un courriel ou une alerte de supervision.
logger -p daemon.err -t imagunity "Échec de $1 sur $(hostname) : voir journalctl -u $1"
EOF
chmod 0755 /etc/imagunity/alerte      # lancé sous le compte imagunity-maj, sans privilège
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
| Journal | `journalctl -u imagunity-box -f` ; choix de la version : `journalctl -u imagunity-box-choix` ; mises à jour : `journalctl -u imagunity-box-maj` |
| Transferts | console `http://<ip>:8080/`, ou `curl -s $U http://<ip>:8080/api/v1/overview` et `…/api/v1/transfers` |
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
| `Interactive authentication required` dans `journalctl -u imagunity-box-maj` | Règle polkit absente ou polkit non installé : `dnf -y install polkit`, puis relancer `installer.sh` |
| `imagunity-box-choix.service` en échec : la box ne démarre pas | Aucune version valide, ou empreinte de l'éditeur différente : `journalctl -u imagunity-box-choix` |

## Annexe : préparer l'archive (éditeur)

L'archive se prépare à partir d'une livraison signée ([livrer une version](livraison.md)) : le workflow Livraison construit la box, puis l'éditeur la signe hors ligne, avec le registre des numéros.

```bash
java -jar box/imagunity-release.jar signer --cles editeur.json --artefact box/imagunity-box.jar \
    --registre deploy/livraison/registre-versions.json
sh deploy/box-systemd/construire-archive.sh box              # → imagunity-box-0.15.0.tar.gz
```

L'archive contient :
- le lanceur (`imagunity-release.jar`) ;
- la version signée de la box et son manifeste ;
- les scripts, les services systemd, l'installateur et l'exemple de configuration.

La même version signée se publie sur le serveur de mises à jour ([guide du site central, § 9](installation-site-central-rocky10.md)).
