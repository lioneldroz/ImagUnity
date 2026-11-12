# Réception DICOMweb STOW-RS

> Objectif (pilote, priorité 5 du [plan](plan-de-realisation.md), second volet) : le PACS peut déposer des examens dans la box **par HTTP** (DICOMweb STOW-RS, PS3.18 § 10.5), en plus du C-STORE. Beaucoup de PACS et de viewers récents le préfèrent.
> Statut : **fait**, et vérifié sur la pile Docker avec Orthanc comme client STOW-RS (§ 5).

## 1. Requête

```
POST /dicomweb/{AET}/studies            instances
POST /dicomweb/{AET}/studies/{Study}    instances de cette étude seulement
Content-Type: multipart/related; type="application/dicom"; boundary=…
Authorization: Basic …                  compte DICOMweb de la box
```

- `{AET}` est la **destination DICOM**, comme l'AE Title appelé en C-STORE. Une route fixe (`IU_TO_B`) désigne un partenaire. La destination de routage (`IU_AUTO`) applique les [règles de routage par champ DICOM](routage-dicom.md).
- Chaque partie est une instance **DICOM Part 10** (`application/dicom`). Les formes « métadonnées JSON/XML + données binaires » de STOW-RS ne sont pas prises en charge : réponse 415.
- Le corps est lu **en flux** : les instances ne passent pas par la mémoire, et le délimiteur peut se trouver n'importe où dans les lectures.

## 2. Même traitement que le C-STORE

Chaque instance suit exactement le chemin d'une instance reçue par C-STORE :
- contrôles des UID et de l'espace disque ;
- routage, avec une copie par partenaire ;
- rattachement à son étude, puis transfert quand l'étude est complète.

Une étude peut d'ailleurs arriver en partie par C-STORE et en partie par STOW-RS. Dans le suivi, l'émetteur apparaît sous la forme `dicomweb:<compte>`.

Avec `/studies/{Study}`, une instance d'une autre étude est refusée (Failure Reason `A900H`).

## 3. Réponse

DICOM JSON (`application/dicom+json`) :
- **Referenced SOP Sequence** `(0008,1199)` : les instances acceptées (classe et instance SOP) ;
- **Failed SOP Sequence** `(0008,1198)` : les instances refusées, avec **Failure Reason** `(0008,1197)`. Par exemple, `0110H` pour aucune règle de routage ou une destination inconnue, `C000H` pour un fichier illisible ou qui n'est pas en Part 10, `A700H` pour un disque plein, `A900H` pour une autre étude que celle de l'URL.

| Code HTTP | Sens |
|---|---|
| 200 | Toutes les instances acceptées |
| 202 | Une partie seulement : voir Failed SOP Sequence |
| 409 | Aucune instance acceptée |
| 400 | Requête mal formée (délimiteur absent, corps illisible, aucune instance, UID de l'URL invalide) |
| 415 | Pas `multipart/related; type="application/dicom"` |
| 401 / 403 | Pas de compte, ou compte sans ce droit |
| 404 | Entrée DICOM désactivée sur cette box |

## 4. Accès

- Un **compte DICOMweb** dédié (`IMAGUNITY_DICOMWEB_USER`, par défaut `pacs` ; `IMAGUNITY_DICOMWEB_PASSWORD`, en clair ou haché `{bcrypt}…`). Sans mot de passe, la réception STOW-RS est fermée.
- Chaque compte n'ouvre que sa partie :
  - le compte DICOMweb ne donne accès qu'à `/dicomweb/**`, pas à la console ;
  - le compte opérateur ne peut pas déposer d'instances ;
  - les deux comptes doivent être distincts.
- Le dépôt DICOMweb est dispensé de l'en-tête `X-ImagUnity-Console`, qui protège la console des requêtes intersites. Un PACS ne l'envoie pas, et ce compte n'ouvre pas la console.
- Comme la console, l'entrée HTTP est réservée au réseau du site (`SERVER_ADDRESS`), derrière un proxy TLS en production.

## 5. Vérifié en réel (pile Docker, novembre 2026)

Orthanc A, avec son module DICOMweb, est configuré avec la box A comme serveur DICOMweb (`http://<box>/dicomweb/IU_AUTO`, compte `pacs`). Il dépose des études synthétiques, avec la règle `scanner-chu (Modality=CT, InstitutionName=CHU*) → box-b` :
- **scanner du CHU** (10 instances) déposé par STOW-RS : 10 acceptées, routé vers box B, transfert ACKNOWLEDGED en **6,9 s**, **10/10 identiques** chez B ;
- **échographie** qu'aucune règle ne couvre : les 2 instances sont refusées (réponse 409), et aucun transfert n'est créé. Orthanc présente ce refus comme une erreur de son module (« HTTP 500 ») ;
- **comptes** : le compte opérateur reçoit 403 sur `/dicomweb`, un mauvais mot de passe 401, une requête anonyme 401.

L'essai a révélé un écart à la norme chez Orthanc : son délimiteur multipart fait 73 caractères, alors que la RFC 2046 en autorise 70 au plus. La box accepte désormais jusqu'à 200 caractères.

## 6. Reste à faire

- Formes métadonnées + données binaires de STOW-RS, si un client le demande.
- `RetrieveURL` dans la réponse : la box ne sert pas de WADO-RS, puisque les études ne font qu'y transiter.
