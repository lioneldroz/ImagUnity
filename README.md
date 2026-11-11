# ImagUnity

ImagUnity est une box qui transporte des examens d'imagerie DICOM entre deux établissements partenaires, où qu'ils soient dans le monde, **sans ouvrir aucun port entrant**.

Une box s'installe de chaque côté. Entre les deux, des relais ne voient passer que des données chiffrées de bout en bout.

## Principe : la poste restante

L'émetteur dépose l'examen chiffré sur un relais, et le destinataire vient le chercher quand il est prêt.

- **Connexions sortantes uniquement** : chaque box ouvre elle-même ses connexions vers les relais. Il suffit que le pare-feu autorise une connexion TLS sortante.
- **Relais aveugles** : les relais stockent des morceaux chiffrés dont ils n'ont pas les clés, ne connaissent ni l'émetteur ni le destinataire, et effacent chaque morceau une fois retiré.
- **Asynchrone** : l'émetteur et le destinataire n'ont pas besoin d'être connectés en même temps. Une coupure réseau retarde le transfert sans l'interrompre.

## Architecture

| Élément | Rôle |
|---|---|
| Box émettrice / réceptrice | Intégration PACS, routage DICOM, compression, chiffrement, découpage |
| Relais SMP | Signalisation : annonce qu'un examen attend et comment le récupérer |
| Relais XFTP | Transport du contenu volumineux, découpé en morceaux chiffrés |
| Annuaire | Propre à l'organisation : clés publiques et adresses des files SMP des destinataires |

ImagUnity s'appuie sur deux protocoles de l'écosystème [SimpleX](https://simplex.chat) : **SMP** (SimpleX Messaging Protocol) et **XFTP** (SimpleX File Transfer Protocol).

### Déroulé d'un envoi

1. Le PACS envoie l'examen à la box émettrice, comme à n'importe quelle destination DICOM.
2. La box identifie le destinataire (destination DICOM ou champ DICOM), puis récupère dans l'annuaire sa clé publique et l'adresse de sa file SMP.
3. Elle compresse l'examen sans perte, le chiffre pour le destinataire, le découpe et dépose les morceaux sur un ou plusieurs relais XFTP.
4. Elle envoie la description du fichier dans la file SMP du destinataire.
5. La box réceptrice télécharge les morceaux, vérifie leurs empreintes, déchiffre et décompresse l'examen.
6. Elle transmet l'examen au mini-PACS et renvoie un accusé de bout en bout par une file SMP de retour.
7. Les relais suppriment les morceaux après leur retrait ou à l'expiration de leur durée de séjour.

## Organisation du dépôt

```
backend/     Java 25, Spring Boot 4, Maven multi-modules
  domain/       modèle métier, machine à états des transferts
  dicom/        adaptateur DICOM (dcm4che)
  packaging/    manifeste, compression zstd, découpage par série
  transport/    port de transport et adaptateur simplex-chat
  persistence/  état des transferts et partenaires (H2 + Flyway)
  orchestration/ orchestrateur des transferts, appairage
  api/          API REST (+ SSE) pour la console
  app/          assemblage Spring Boot de la box
frontend/    console React + TypeScript (Vite)
deploy/      environnement Docker Compose de la preuve de concept
docs/        note d'architecture et plan de réalisation
```

## Développement

Prérequis : JDK 25, Node.js 24, Docker (pour l'environnement de test).

Backend (API sur http://localhost:8080) :

```bash
cd backend
./mvnw verify
java -jar app/target/imagunity-box.jar
```

`./mvnw verify` mesure la couverture des tests (JaCoCo) et **échoue sous 95 % des lignes ou 75 % des branches**, module par module. Rapport par module : `<module>/target/site/jacoco/index.html`. Seuils modifiables en ligne de commande : `-Dcouverture.lignes.min=0.90 -Dcouverture.branches.min=0.70`.

Frontend (http://localhost:5173, les appels `/api` sont relayés vers le backend) :

```bash
cd frontend
npm install
npm run dev
```

Environnement complet (relais, deux box, deux Orthanc) : voir [deploy/README.md](deploy/README.md).

## Documentation

- [Note d'architecture](docs/ImagUnity%20—%20Note%20d'architecture.docx)
- [Plan de réalisation](docs/plan-de-realisation.md)
- [Phase 1 — Exploration SimpleX](docs/phase1-simplex.md)
- [Phase 2 — Entrée DICOM](docs/phase2-dicom.md)
- [Phase 3 — Empaquetage](docs/phase3-empaquetage.md)
- [Phase 4 — Orchestrateur](docs/phase4-orchestrateur.md)
- [Phase 5 — Réception et restitution](docs/phase5-reception.md)
- [Phase 6 — Accusé de bout en bout](docs/phase6-accuse.md)
- [Phase 7 — API et console React](docs/phase7-console.md)
- [Phase 8 — Campagne de tests](docs/phase8-campagne.md)
- [Spécification du protocole de transport IU-T](docs/specification-protocole.md) (brouillon)

## Statut

Preuve de concept terminée (phases 0 à 8) : la chaîne PACS A → box A → relais SimpleX → box B → PACS B fonctionne, avec accusé de bout en bout et suivi en direct dans la console. La campagne de tests (pare-feu, liaison bridée, coupure, destinataire éteint, 2 Go) est passée en réel, toutes instances identiques à l'arrivée ; voir le [rapport](docs/phase8-campagne.md).
