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

## Documentation

- [Note d'architecture](docs/ImagUnity%20—%20Note%20d'architecture.docx)

## Statut

Projet en phase de conception.
