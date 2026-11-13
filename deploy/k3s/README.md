# Relais IU-T sur K3s

Relais réparti ([spécification § 6.8](../../docs/specification-protocole.md)) : plusieurs instances sans état du relais derrière le Traefik fourni par K3s, sur une base PostgreSQL et un stockage compatible S3 partagés. Les box ne sont pas dans le cluster : ce sont des appareils sur les sites, qui joignent le relais par une connexion sortante.

| Fichier | Contenu |
|---|---|
| `kustomization.yaml` | le relais seul, sur une base PostgreSQL et un stockage S3 fournis à part (production) |
| `relais.yaml` | configuration, déploiement (2 instances, mise à jour une instance à la fois), budget de disponibilité, service, entrée Traefik en HTTPS limitée à l'API `/iu/`, nouvel essai sur une autre instance |
| `traefik-config.yaml` | réglages du Traefik de K3s : délais compatibles avec les attentes longues, journaux d'accès coupés |
| `essai/` | le relais avec PostgreSQL et SeaweedFS **d'essai** (une instance chacun, sans sauvegarde) : `kubectl apply -k deploy/k3s/essai` |
| `secrets.exemple.yaml` | exemple de secrets (base, compte S3), à remplacer |

## Sans registre d'images

Construire l'image du relais, puis l'importer dans le containerd de chaque nœud K3s :

```bash
# Depuis les sources (l'étape de construction télécharge les dépendances Maven) :
docker build -t imagunity/relay:0.8.0 -f deploy/relay/Dockerfile backend
# Ou à partir d'un jar déjà construit, sans réseau (comme la campagne) :
#   (cd backend && ./mvnw -pl relay -am -DskipTests package) && sh deploy/campagne/prepare.sh
#   docker build -t imagunity/relay:0.8.0 -f deploy/campagne/relay.Dockerfile deploy/campagne/build
docker save imagunity/relay:0.8.0 | sudo k3s ctr images import -
```

Régler la version dans `kustomization.yaml` (`images.newTag`). `imagePullPolicy: IfNotPresent` : K3s n'essaie pas de télécharger une image déjà importée. Faire de même pour `postgres:17` et `chrislusf/seaweedfs:3.97` si les nœuds n'ont pas d'accès à Internet.

## Installation

```bash
kubectl apply -f deploy/k3s/namespace.yaml
kubectl -n imagunity-relais create secret generic relais-base --from-literal=password=<mot de passe>
kubectl -n imagunity-relais create secret generic relais-s3 --from-literal=access-key=<…> --from-literal=secret-key=<…>
kubectl -n imagunity-relais create configmap relais-organisation --from-file=organisation.pub.json
kubectl -n imagunity-relais create secret tls relais-tls --cert=relais.crt --key=relais.key
kubectl apply -k deploy/k3s/essai     # essai, avec base et S3 à une instance
# production : base et S3 fournis à part (adresses dans relais.yaml), puis
kubectl apply -k deploy/k3s
```

Avant : dans `relais.yaml`, l'adresse de l'annuaire, l'empreinte du responsable (vérifiée hors bande) et le nom d'hôte de l'entrée ; dans `essai/s3.yaml`, le compte S3 (le même que le secret `relais-s3`).

- **TLS** : l'entrée n'écoute qu'en HTTPS (point d'entrée `websecure`), avec le certificat du secret `relais-tls` (certificat de l'établissement, ou cert-manager). L'adresse publiée dans l'annuaire des box est `https://<hôte>`. Seul `/iu/` est routé : `/actuator` (santé, métriques) n'est joignable que dans le cluster.
- **Production** : remplacer `essai/` par une base répliquée (opérateur CloudNativePG, ou base gérée) et un stockage S3 redondant (MinIO distribué, Garage, ou service S3 externe). Trois nœuds K3s pour survivre à la perte d'une machine ; une seule machine ne protège que contre la panne d'une instance.
- **Mise à jour** : nouvelle image, `newTag`, `kubectl apply -k` : les instances sont remplacées une à une (`maxUnavailable: 0`), les attentes longues en cours se terminent (arrêt propre en 40 s, `terminationGracePeriodSeconds: 60`).

## Vérifié

- Manifestes acceptés par le serveur d'API de K3s v1.33.5 (`kubectl apply -k --dry-run=server`), avec les définitions de Traefik du chart fourni par K3s ; réglages de Traefik rendus par ce chart (`--entryPoints.web.transport.respondingTimeouts.readTimeout=5m`, journaux d'accès coupés). Le passage en HTTPS seul et la séparation de `essai/` (pré-audit) n'ont été vérifiés que pour la syntaxe : à repasser au `--dry-run=server`.
- Comportement des instances (répartition, arrêt d'une instance, redémarrage une à une, stockage S3) vérifié sur la pile Compose équivalente ([spécification § 13.4](../../docs/specification-protocole.md)).
- **Pas encore vérifié** : le fonctionnement des pods dans un vrai cluster K3s. L'environnement d'essai ne permettait pas de lancer des conteneurs dans K3s lui-même conteneurisé. À faire sur une machine K3s : `kubectl apply -k`, puis la campagne avec les box pointées sur l'entrée.
