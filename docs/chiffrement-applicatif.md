# Chiffrement applicatif des paquets

> Objectif (pilote, priorité 2 du [plan](plan-de-realisation.md)) : chiffrer chaque paquet **pour la box destinataire**, avec les clés publiées dans son entrée d'[annuaire signé](annuaire-signe.md). Ce chiffrement s'ajoute à celui du transport. Il tient même si le transport, un relais ou un fichier copié est compromis, et il résiste au calcul quantique.
> Statut : **fait**, et vérifié sur la pile Docker (§ 7).

## 1. Principes

```
Box A                                                             Box B
paquet .tar.zst ──chiffré pour B──► paquet .tar.zst.iu1 ──relais──► déchiffré avec les clés de B
                   (clés de B lues                                   ──► dépaqueté ──► PACS B
                    dans l'annuaire)
```

- **Deux couches.** Le transport SimpleX chiffre déjà de bout en bout. Le chiffrement applicatif en ajoute une seconde, qui ne dépend que des clés de l'annuaire. Il ne dépend ni de SimpleX, ni du futur transport IU-T.
- **Les clés de chiffrement sont dans l'annuaire.** Chaque box a une paire de clés de chiffrement, générée dans la box, à côté de sa clé de signature. Sa demande d'enrôlement, signée par la box, contient la clé publique de chiffrement. Le responsable la recopie dans l'entrée qu'il signe. Un annuaire compromis ne peut donc pas substituer ses propres clés.
- **Confidentialité ici, authenticité dans l'annonce.** Le chiffrement garantit que seule la box B lit le paquet. L'annonce, signée par la box A, porte la taille et l'empreinte SHA-256 du paquet **chiffré**. B vérifie cette empreinte avant de déchiffrer : un paquet substitué est refusé.
- **Uniquement des primitives standard du JDK 25.** Aucune bibliothèque cryptographique ajoutée.

## 2. Cryptographie (suite `IU1`)

| Élément | Choix |
|---|---|
| Accord de clé classique | DHKEM X25519 (RFC 9180), API `KEM` du JDK |
| Accord de clé post-quantique | ML-KEM-768 (FIPS 203), API `KEM` du JDK |
| Combinaison | HKDF-SHA256 des deux secrets (ML-KEM, puis X25519) ; en contexte, `IU1-paquet` et l'empreinte de l'en-tête du fichier |
| Chiffrement du contenu | AES-256-GCM, par blocs de 1 Mio |
| Clé | Neuve pour chaque fichier : deux encapsulations par paquet |

La clé tient **tant que l'un des deux algorithmes tient**. C'est le principe hybride déjà retenu pour les signatures (Ed25519 et ML-DSA-65). L'en-tête, qui contient les deux encapsulations, l'empreinte de la clé du destinataire et le nom du fichier, entre dans la dérivation de la clé : modifier un seul de ses octets rend le fichier indéchiffrable.

## 3. Format d'un paquet chiffré (`.iu1`)

```
"IU1E" | version (1) | longueur de l'en-tête (4 octets) | en-tête JSON
bloc 0 | bloc 1 | … | dernier bloc            chaque bloc : AES-256-GCM, étiquette de 16 octets
```

```json
{ "suite": "IU1", "recipient": "<empreinte de la clé de chiffrement de B>",
  "name": "<groupe>-1-of-4.tar.zst.iu1",
  "x25519": "<encapsulation, base64>", "mlKem768": "<encapsulation, base64>", "chunkSize": 1048576 }
```

- Chaque bloc a pour nonce **son rang**. Ses données associées sont l'empreinte de l'en-tête et un indicateur « dernier bloc ». Un bloc modifié, retiré, déplacé ou dupliqué est refusé, de même qu'un fichier tronqué ou allongé.
- Le fichier se chiffre et se déchiffre **au fil de l'eau** : un paquet de 900 Mo n'est jamais chargé en mémoire.
- Le surcoût vaut environ 1,5 ko d'en-tête, plus 16 octets par Mio, soit 0,002 %.
- À l'ouverture, B vérifie que le fichier lui est destiné (`recipient`) et qu'il porte le nom annoncé (`name`).

## 4. Dans la box

**Envoi.** Chaque paquet est chiffré au moment d'être confié au transport, pour les clés de l'entrée de la box destinataire :
1. le paquet chiffré `….tar.zst.iu1` est écrit à côté du paquet en clair ;
2. son fichier, sa taille et son empreinte sont enregistrés à la place de ceux du paquet en clair ;
3. le paquet en clair est supprimé.

Un arrêt entre deux étapes est rattrapé au redémarrage. Les paquets ne sont pas chiffrés dès l'empaquetage, mais à l'envoi. Ainsi, un envoi refusé faute de clés part chiffré à la relance, une fois l'entrée réémise. Une relance renvoie les paquets déjà chiffrés tels quels.

**Réception.** Après vérification de la taille et de l'empreinte annoncées, chaque paquet est déchiffré dans le dossier de travail, dépaqueté, puis sa copie en clair est supprimée. Un paquet qui ne se déchiffre pas met le transfert en échec. L'accusé « en échec » en donne la cause à l'émetteur, qui garde ses paquets.

**Clés.** Les clés privées de chiffrement sont dans `identite-box.json.chiffrement`, à côté de la clé de signature, en droits 600. Elles sont chiffrées par la **même phrase de passe** (voir l'[annuaire signé, § 9](annuaire-signe.md#9-protection-des-clés-privées)). Une box antérieure crée ses clés de chiffrement au démarrage suivant, sans changer d'identifiant, et journalise que son entrée est à réémettre.

**Exigence.** Avec l'annuaire, le chiffrement est **obligatoire**, sans réglage :
- on refuse d'envoyer à une box dont l'entrée ne publie pas de clés de chiffrement. Message : « Box du partenaire « … » sans clés de chiffrement dans l'annuaire (entrée à réémettre…) » ;
- on refuse de restituer un paquet reçu en clair (« Paquet reçu sans chiffrement applicatif, refusé »).

L'ancien réglage `IMAGUNITY_DIRECTORY_ENCRYPTION_REQUIRED=false`, qui laissait passer ces paquets en clair le temps de migrer un parc, a été retiré (version 0.15.0). Une box qui le porte encore **refuse de démarrer** et l'indique : retirer la ligne de sa configuration. Sans annuaire, les paquets partent en clair, comme avant ; le transport reste chiffré.

**Console.** L'onglet Annuaire signale une box active dont l'entrée n'a pas de clés de chiffrement : « sans clés de chiffrement : entrée à réémettre ».

## 5. Migrer un parc existant

1. **Mettre à jour le service d'annuaire.** Une version antérieure refuse les entrées qui portent des clés de chiffrement, car il refuse tout champ inconnu.
2. **Mettre à jour les box.** Chacune crée ses clés de chiffrement au démarrage.
3. **Réémettre chaque entrée** à partir d'une nouvelle demande d'enrôlement (console, « Télécharger la demande d'enrôlement »), avec un numéro supérieur :
   ```bash
   # ADMIN : l'outil du responsable (voir l'annuaire signé, § 4)
   $ADMIN enroler --cles org.json --demande demande-b.json --serie 2 --sortie entree-b.json
   $ADMIN verifier --cle-publique org.json.pub.json --entree entree-b.json   # « Clés de chiffrement publiées »
   $ADMIN publier --annuaire https://annuaire.exemple --entree entree-b.json
   ```
   `enroler` avertit si la demande ne contient pas de clés de chiffrement.
4. En attendant la réémission, les envois vers une box sans clés sont refusés avec une cause explicite. On les **relance** depuis la console une fois l'entrée publiée.

Cette migration se fait avec une version antérieure à 0.15.0 si le parc doit continuer d'échanger pendant la réémission : seules ces versions acceptent encore des paquets en clair ([notes de version](notes-de-version.md)).

Une entrée sans clés de chiffrement ne contient pas le champ `encryptionKeys`. Elle reste donc lisible par les box antérieures.

## 6. Ce que cela protège, et ce que cela ne protège pas

- **Protège.** Un relais, un opérateur de transport ou quiconque copie un paquet ne lit rien de l'examen, même si le chiffrement du transport était cassé, y compris plus tard par un calculateur quantique (« récolter maintenant, déchiffrer plus tard »). Un paquet destiné à B ne s'ouvre pas chez C.
- **Ne protège pas** une box compromise en fonctionnement : ses clés sont en mémoire. Les mesures contre ce risque restent la révocation dans l'annuaire et le support matériel des clés.
- **Pas de confidentialité persistante par message.** Chaque fichier a une clé neuve, mais celle-ci dérive de la clé à long terme de B. Qui vole plus tard la clé privée de B **et** a gardé les paquets pourra les déchiffrer. Pour s'en prémunir, on renouvelle la clé de chiffrement par réémission de l'entrée, et on garde les paquets peu de temps sur les relais : ils en sont retirés dès l'accusé.

## 7. Vérifié en réel (pile Docker, novembre 2026)

Sur la chaîne de la campagne de tests (relais SimpleX, deux box, deux Orthanc, annuaire), avec des données synthétiques :
- **mise à jour des box en service** : clés de chiffrement créées au démarrage, protégées par la phrase de passe (droits 600, PBKDF2 et AES-256-GCM), **mêmes identifiants** ;
- **avant réémission des entrées**, l'envoi d'une étude est refusé : « Chiffrement du paquet 1/1 impossible : Box du partenaire « box-b » sans clés de chiffrement dans l'annuaire… » ;
- le service d'annuaire de la version précédente **refusait** les nouvelles entrées (champ inconnu) : d'où l'ordre de migration du § 5 ;
- **entrées réémises** (n° 2, « Clés de chiffrement publiées »), puis **relance** de l'envoi refusé : paquet `….tar.zst.iu1` téléchargé par B, 5 instances restituées, accusé reçu ;
- **scénario nominal** (100 instances, 50 Mo) chiffré : ACKNOWLEDGED en 13,4 s, **100 instances identiques sur 100**.

## 8. Reste pour le pilote

- **Renouvellement des clés de chiffrement** sans changer d'identité : nouvelle demande, nouvelle entrée, avec une période de recouvrement pour les paquets en route.
- **Support matériel** des clés de chiffrement, comme pour les clés de signature, dès que les TPM et HSM prendront en charge ML-KEM.
