# Présentation « ImagUnity — le voyage d’un examen »

Présentation de 14 diapositives qui explique simplement le fonctionnement d’ImagUnity, en suivant un examen (données synthétiques) du PACS de l’hôpital A jusqu’au PACS de l’hôpital B. Public visé : non technique ; les détails sont dans les notes du présentateur.

- **À voir et à présenter** : [artefact Claude Slides](https://claude.ai/artifact/NdNbNfzQsYiio1NWJok3oq) (privé : à partager depuis le menu Partager de la page). Export PowerPoint ou PDF depuis ce même menu.
- **Sources** (ce dossier) : `deck.json` (titre, ordre, sections, polices) et une diapositive par fichier dans `slides/`, au format HTML de Claude Slides. Elles servent à suivre les modifications et à reconstruire la présentation ; ouvertes seules dans un navigateur, les formes et icônes (`x-shape`, `x-icon`) ne s’affichent pas.
- La capture de la console (diapositive « états ») est [`../images/phase7-console.png`](../images/phase7-console.png) ; dans les sources, elle est référencée par son adresse dans l’artefact (`/_blob/…`).

| # | Diapositive | Fichier |
|---|---|---|
| 1 | Couverture | `slides/cover.html` |
| 2 | Le problème | `slides/probleme.html` |
| 3 | L’idée : une boîte aux lettres aveugle | `slides/idee.html` |
| 4 | Qui fait quoi | `slides/acteurs.html` |
| 5 | L’appairage | `slides/appairage.html` |
| 6 | Le voyage d’un examen, en 8 étapes | `slides/flux.html` |
| 7 | Le PACS envoie, la box rassemble l’étude | `slides/etape1.html` |
| 8 | L’empaquetage | `slides/etape2.html` |
| 9 | Le dépôt et l’annonce | `slides/etape3.html` |
| 10 | La box B télécharge, puis vérifie tout | `slides/etape4.html` |
| 11 | La restitution, puis l’accusé de réception | `slides/etape5.html` |
| 12 | Chaque transfert a un état, visible en direct | `slides/etats.html` |
| 13 | Quand ça se passe mal, tout reprend seul | `slides/incidents.html` |
| 14 | Ce que la campagne de tests a montré | `slides/preuves.html` |

Les chiffres viennent de la [campagne de tests](../phase8-campagne.md).
