# Contrôles d'accessibilité (RGAA 4.1.2)

Tests automatiques des interfaces web d'ImagUnity au regard du [RGAA 4.1.2](https://accessibilite.numerique.gouv.fr/methode/), lancés par le job **Accessibilité (RGAA 4.1.2)** de la CI (`.github/workflows/ci.yml`).

**Interfaces couvertes** :
- administration du service d'annuaire (`/admin/`, fichiers de `backend/directory-server/src/main/resources/static/admin/`) : `tests/admin.spec.js` ;
- interface locale du responsable (outil du poste du responsable, fichiers de `backend/directory/src/main/resources/com/imagunity/directory/interface/`) : `tests/responsable.spec.js`, dans chacun des huit onglets, sans organisation et avec, et dans les fenêtres de signature et de confirmation ;
- console de la box (React, `frontend/`), **telle qu'elle est construite** (`frontend/dist`) : `tests/console.spec.js`, flux d'événements de la box simulé lui aussi.

## Ce qui est contrôlé

Chaque test tourne avec une API simulée : aucun service Java n'est démarré. Les interfaces n'ont qu'un thème clair ; un test par interface vérifie qu'elles le gardent quand le système est réglé en sombre.

| Contrôle | Critères RGAA | Outil |
|---|---|---|
| Validité du code, en-têtes de tableau (`scope`) | 8.2, 5.7 | html-validate (`npm run html`) |
| Règles rattachées au RGAA (étiquette `RGAAv4`) et à WCAG 2.1 A/AA, page chargée, document préparé, règles essayées, annuaire vide, brouillon refusé | 33 critères en partie : 1.1, 3.2, 5.4, 5.7, 6.1, 8.3, 11.1, 11.2, 12.7… | axe-core |
| Titre associé à chaque tableau | 5.4 | Playwright |
| Contour des champs (3:1) et texte d'exemple (4,5:1) | 3.3, 3.2 | mesure maison |
| Messages de statut en `role="status"`, jamais masqués, et annoncés de nouveau quand ils se répètent | 7.5 | mesure maison |
| Page lisible à 320 px de large, puis avec l'espacement du texte agrandi, sans défilement horizontal (hors tableaux et code) | 10.11, 10.12 | mesure maison |
| Lien d'évitement en premier, qui mène au contenu ; indicateur de focus sur chaque élément atteint au clavier | 12.7, 10.7 | Playwright |
| *Interface du responsable* : chaque champ nommé autrement que par son texte d'exemple ; cases des routes groupées sous une légende ; champs obligatoires signalés | 11.1, 11.5, 11.10 | mesure maison |
| *Interface du responsable* : urgence des tâches écrite, fenêtres de dialogue nommées, focus conservé par l'actualisation automatique (toutes les 30 s) | 3.1, 7.1, 7.3 | Playwright |
| *Interface du responsable* : bulles d'aide fermées par Échap, et entières dans l'écran à 320 px | 10.13, 10.11 | Playwright |
| *Console* : détail des paquets d'un envoi ouvert et fermé au clavier (`aria-expanded`), focus conservé après une relance et un appairage | 7.1, 7.3 | Playwright |
| *Console* : état du flux et résultats (appairage, relance) annoncés ; champ d'appairage avec étiquette visible et contour contrasté ; tableaux titrés, en-têtes déclarés | 7.5, 11.1, 3.3, 5.4, 5.7 | mesure maison |

La taille des cibles (WCAG 2.2) n'est pas contrôlée : elle ne fait pas partie du RGAA 4.1.2.

## Limites

Ces tests évitent les régressions sur ce qui se mesure. Ils **ne suffisent pas à déclarer la conformité** : la pertinence des intitulés, des titres et des alternatives, la restitution réelle par les lecteurs d'écran (NVDA, JAWS, VoiceOver) et une bonne partie des thèmes 7 à 13 demandent un audit manuel.

## En local

Prérequis : Node.js 24. La console de la box se construit d'abord (`tests/console.spec.js` le rappelle sinon) :

```bash
(cd frontend && npm ci && npm run build)
cd accessibilite
npm ci
npx playwright install chromium
npm test
```

Pour éprouver une autre version d'une interface (par exemple une ancienne) : `ADMIN_STATIQUE=/chemin/vers/admin`, `RESPONSABLE_STATIQUE=/chemin/vers/interface` ou `CONSOLE_STATIQUE=/chemin/vers/dist` (console construite), puis `npx playwright test`.

La règle `autocomplete-password` du validateur est désactivée : la phrase de passe de la clé du responsable porte volontairement `autocomplete="off"`, pour qu'un gestionnaire de mots de passe ne la mémorise pas (le RGAA, critère 11.13, ne vise que les données personnelles de l'utilisateur).

En cas d'échec, la CI joint le rapport Playwright (`rapport-accessibilite`) : il contient les résultats complets d'axe-core de chaque état testé.

## Ajouter une interface

Un fichier `tests/<interface>.spec.js` par interface, sur le modèle de `tests/admin.spec.js` : `servir()` (fichiers statiques et API simulée), `axe()` et les mesures de `tests/outils.js`. Ajouter ses pages HTML au script `html` de `package.json`.
