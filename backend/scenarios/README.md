# Tests de scénarios (non-régression)

Ce module rejoue, à chaque `./mvnw verify`, les situations qu'un établissement peut rencontrer : envoi d'un examen d'une box à l'autre en DICOM ou en DICOMweb, routage, refus à l'entrée, pannes, révocation et expiration dans l'annuaire. Chaque scénario vérifie le résultat de bout en bout, jusqu'au PACS de l'établissement destinataire.

Ce ne sont pas des tests unitaires : rien n'est simulé entre les deux PACS.

## Ce qui tourne

Le banc ([`Bench`](src/test/java/com/imagunity/scenarios/Bench.java)) démarre une organisation complète dans la JVM des tests, en quelques secondes :

```
PACS de A ──C-STORE / STOW-RS──► box A ──IU-T──► relais IU-T ──IU-T──► box B ──C-STORE──► PACS de B
   (simulé)                     (réelle)        (réel)               (réelle)              (simulé)
                                   └──────────── service d'annuaire signé (réel) ────────────┘
```

| Élément | Nature | Détail |
|---|---|---|
| Responsable de l'organisation | réel | sa paire de clés signe les entrées, les renouvellements et les révocations |
| Service d'annuaire | réel (`directory-server`) | base H2 en mémoire, publication par le compte du responsable |
| Relais IU-T | réel (`relay`) | base H2 en mémoire, morceaux sur disque ; il n'accepte que les box actives de l'annuaire et peut être arrêté puis redémarré |
| Box | réelles (`app`) | base H2 et dossiers sur disque : une box arrêtée puis redémarrée reprend son état ; entrée DICOM, DICOMweb, console et API sur la boucle locale |
| PACS de chaque établissement | simulés ([`FakePacs`](src/test/java/com/imagunity/scenarios/FakePacs.java), [`PacsClient`](src/test/java/com/imagunity/scenarios/PacsClient.java)) | dcm4che ; le PACS récepteur garde les instances reçues, peut refuser les instances ou être arrêté |

Chaque box est enrôlée comme en production :

1. sa demande d'enrôlement est lue par l'API de la console ;
2. le responsable la signe ;
3. l'entrée signée est publiée dans l'annuaire ;
4. la box A est appairée à la box B par l'API de la console (`POST /api/v1/directory/boxes/{id}/pair`), et la poignée de main signée lie les deux box.

Les paquets voyagent chiffrés pour la box destinataire et l'accusé revient signé.

Données exclusivement synthétiques : patient « SYNTHETIQUE^SCENARIO », pixels pseudo-aléatoires ([`Studies`](src/test/java/com/imagunity/scenarios/Studies.java)). Les mots de passe sont des mots de passe d'essai.

## Scénarios

### Transfert DICOM nominal : [`DicomTransferScenarioTest`](src/test/java/com/imagunity/scenarios/DicomTransferScenarioTest.java)

| Scénario | Vérifié |
|---|---|
| Un examen de 3 séries est envoyé en C-STORE par le PACS de A | Chaque instance arrive dans le PACS de B avec les mêmes UID, le même patient et les mêmes pixels. A reçoit l'accusé (`ACKNOWLEDGED`), B a tout restitué (`RESTORED`, 12 instances) et rien ne revient au PACS de A. |
| Un examen volumineux dépasse la taille maximale d'un paquet | Il est découpé en plusieurs paquets, tous déposés sur le relais, et restitué en entier. |
| Plusieurs examens sont envoyés à la suite | Tous arrivent, chacun avec son accusé. |
| Le PACS vérifie sa liaison avec la box (C-ECHO) | La box répond. |

### Dépôt DICOMweb STOW-RS : [`DicomWebScenarioTest`](src/test/java/com/imagunity/scenarios/DicomWebScenarioTest.java)

| Scénario | Vérifié |
|---|---|
| Le PACS de A dépose l'examen par HTTP (`POST /dicomweb/IU_TO_B/studies`) | Réponse 200 en `application/dicom+json`, qui cite chaque instance. L'examen arrive dans le PACS de B, pixels identiques. |
| Dépôt sur l'URL de l'étude (`/studies/<UID>`) | Accepté quand l'UID est celui de l'étude ; refusé (409) quand les instances sont d'une autre étude, et rien ne part. |
| Mauvais mot de passe, ou pas de compte | 401, rien ne part. |
| Dépôt avec le compte de la console | 403 : le compte de l'opérateur n'a pas le rôle DICOMweb. |
| Destination inconnue | 409 (toutes les instances refusées), rien ne part. |
| Corps qui n'est pas du DICOM | 415. |
| Corps multipart tronqué | Refusé (4xx). |

### Routage et entrée DICOM : [`RoutingScenarioTest`](src/test/java/com/imagunity/scenarios/RoutingScenarioTest.java)

Ce banc compte trois établissements : A envoie, B et C reçoivent.

| Scénario | Vérifié |
|---|---|
| Destination fixe `IU_TO_C` | L'examen arrive chez C seulement. |
| Routage par champ DICOM sur `IU_AUTO` : un scanner du CHU (`Modality=CT`, `InstitutionName=CHU*`) | Il part vers B seulement. |
| Routage par champ DICOM : une IRM (`Modality=MR`) | Elle part vers B **et** C, deux transferts accusés. |
| Un examen ne correspond à aucune règle | Le C-STORE est refusé instance par instance et rien ne part. |
| L'émetteur (AET appelant) n'est pas déclaré | L'association est refusée. |
| La destination (AET appelé) est inconnue | L'association est refusée. |
| La destination est déclarée mais aucun partenaire n'a été appairé | L'examen est reçu, puis le transfert échoue avec le motif « Partenaire non appairé : box-d ». |

### Pannes : [`FailureScenarioTest`](src/test/java/com/imagunity/scenarios/FailureScenarioTest.java)

| Scénario | Vérifié |
|---|---|
| Le PACS de B est arrêté | B échoue à restituer et l'accusé « en échec » informe A (`FAILED`, avec son motif). Le PACS revient, l'opérateur de A relance depuis la console : l'examen arrive et A reçoit l'accusé. |
| Le PACS de B refuse les instances | Même déroulé : échec signalé à A, puis succès après la relance. |
| Relance d'un transfert qui n'a pas échoué | Refusée (409). |
| La box B est éteinte pendant l'envoi (poste restante) | A dépose et annonce (`ANNOUNCED`) sans accusé. B redémarre : elle relève sa file, restitue l'examen, et A reçoit l'accusé. |
| Le relais est arrêté pendant l'envoi | L'entrée DICOM reste ouverte et rien n'arrive. Le relais revient : l'examen part de lui-même et arrive. |
| La box A redémarre avant l'accusé | Elle reprend son état : l'accusé est reçu après le redémarrage, sans transfert en double. |

### Annuaire : révocation et expiration : [`RevocationScenarioTest`](src/test/java/com/imagunity/scenarios/RevocationScenarioTest.java)

Chaque scénario part d'une organisation neuve, parce qu'une révocation est définitive.

| Scénario | Vérifié |
|---|---|
| Le responsable révoque la box destinataire B | A voit B révoquée et l'envoi suivant échoue (« révoquée dans l'annuaire »). Une relance échoue de nouveau et rien n'arrive dans le PACS de B. |
| Le responsable révoque la box émettrice A | Le relais refuse les dépôts de A (HTTP 403, `REVOKED`) et le transfert de A échoue. Rien n'est restitué chez B. |
| L'entrée de B expire | L'envoi échoue (« expirée »). Le responsable renouvelle l'entrée, l'opérateur relance, et l'examen arrive. |

## Lancer les scénarios

Ils font partie du build : `./mvnw verify` (ou `./mvnw test`) les lance après les autres modules, en un peu plus de 3 minutes.

Pour ne lancer qu'eux, il faut d'abord compiler les modules dont ils dépendent :

```bash
cd backend
./mvnw test -pl scenarios -am -Dconsole.skip=true
```

Pour un seul scénario :

```bash
./mvnw test -pl scenarios -am -Dconsole.skip=true -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest='RevocationScenarioTest#aRevokedRecipientReceivesNothingMore'
```

`-Dconsole.skip=true` évite de construire la console React, inutile aux scénarios.

## Écrire un nouveau scénario

```java
Bench bench = new Bench();
Box a = bench.box(Box.Spec.of("box-a").route("IU_TO_B", "box-b"));
Box b = bench.box(Box.Spec.of("box-b"));
bench.enroll(a);
bench.enroll(b);
bench.pair(a, b, "box-b");

Studies.Study study = Studies.ct(2, 3);              // 2 séries de 3 images
a.cstore("IU_TO_B", study);                          // ou a.stow("IU_TO_B", study)
Bench.await(() -> "ACKNOWLEDGED".equals(a.sentState(study.uid())), "accusé de B");
assertThat(b.pacs.study(study.uid())).hasSize(6);
```

Outils disponibles :

- **Box** : `Box.stop()` et `Box.start()` éteignent puis rallument une box, qui garde son état.
- **PACS** : `FakePacs.stop()` et `FakePacs.refuse(true)` simulent un PACS en panne.
- **Relais** : `Bench.stopRelay()` et `Bench.startRelay()`.
- **Annuaire** : `Bench.revoke(box, motif)`, `Bench.renew(box, durée)` et `Bench.enroll(box, durée)`.
- **Attentes** : `Bench.await(condition, description)` attend un résultat, et `Bench.never(condition, description, durée)` vérifie qu'un événement ne se produit pas.

Mieux vaut une preuve positive qu'un `never` seul : un état d'échec, un code de refus ou un motif. Un `never` passe aussi quand rien ne s'est produit pour une autre raison.

### Contraintes du banc

Pourquoi la box, le relais et l'annuaire tournent ainsi dans une même JVM :

- Les trois applications publient chacune un `application.yml` et des migrations Flyway sous les mêmes chemins. Chacune reçoit donc les siens, recopiés depuis son module ([`AppFiles`](src/test/java/com/imagunity/scenarios/AppFiles.java)).
- La box démarre par [`BoxApplication`](src/test/java/com/imagunity/scenarios/BoxApplication.java), le même assemblage que `ImagUnityApplication`, mais sans ramasser les composants du relais ni du service d'annuaire.
- Le relais ne dépend pas de Spring Security, qui est pourtant présent sur ce classpath : [`RelaySecurity`](src/test/java/com/imagunity/scenarios/RelaySecurity.java) laisse passer, comme en production, toutes ses requêtes jusqu'à sa propre authentification des requêtes signées.
- Les jars de la box et du service d'annuaire sont reconditionnés par Spring Boot (classes sous `BOOT-INF/`) et ne peuvent pas servir de dépendance. Le module dépend donc de leur pom (`type=pom`), et leurs classes (`app/target/classes`, `directory-server/target/classes`) sont ajoutées au classpath des tests. Le relais publie son jar exécutable à part : son jar ordinaire sert directement.

## Autres tests du dépôt

Les scénarios complètent les tests de chaque module, qui vérifient chaque brique isolément.

| Module | Classes de test | Tests | Couvre notamment |
|---|---:|---:|---|
| `domain` | 6 | 29 | machine à états des transferts, UID DICOM |
| `directory` | 18 | 101 | annuaire signé, clés (phrase de passe, TPM), fédération, fraîcheur, interface du responsable |
| `rules` | 4 | 12 | conditions DICOM, priorité, routage |
| `directory-server` | 6 | 20 | publication, enrôlement, lecture réservée, configuration centralisée |
| `release` | 3 | 17 | versions signées, anti-retour en arrière |
| `protocol` | 5 | 20 | formats IU-T, requêtes signées, rejeu |
| `relay` | 7 | 28 | morceaux, files de messages, limites de débit, relais sans état, S3 |
| `dicom` | 9 | 74 | C-STORE, STOW-RS, multipart, restitution, routage |
| `configuration` | 2 | 11 | règles centrales, priorité rechargée à chaud |
| `packaging` | 4 | 45 | empaquetage, compression, découpage |
| `transport` | 14 | 60 | client IU-T face à un vrai relais, plusieurs relais, reprises |
| `persistence` | 4 | 24 | dépôts JDBC, migrations |
| `orchestration` | 10 | 118 | émission, réception, accusés, chiffrement de bout en bout, appairage |
| `api` | 9 | 35 | API de la console, DICOMweb, événements |
| `app` | 15 | 46 | câblage Spring de la box, sécurité, TPM |

Les essais en réel, sous Docker (pare-feu, liaison bridée, coupure, 2 Go, plusieurs relais), sont dans la [campagne du banc d'essai](../../deploy/banc-essai/README.md).
