# Contrôles d'accessibilité (RGAA 4.1.2)

Tests automatiques des interfaces web d'ImagUnity au regard du [RGAA 4.1.2](https://accessibilite.numerique.gouv.fr/methode/), lancés par le job **Accessibilité (RGAA 4.1.2)** de la CI (`.github/workflows/ci.yml`).

**Interface couverte** : administration du service d'annuaire (`/admin/`, fichiers de `backend/directory-server/src/main/resources/static/admin/`). L'interface du responsable et la console de la box ne sont pas encore couvertes : elles n'ont pas été auditées.

## Ce qui est contrôlé

Chaque test tourne en thème clair et en thème sombre, avec une API simulée : aucun service Java n'est démarré.

| Contrôle | Critères RGAA | Outil |
|---|---|---|
| Validité du code, en-têtes de tableau (`scope`) | 8.2, 5.7 | html-validate (`npm run html`) |
| Règles rattachées au RGAA (étiquette `RGAAv4`) et à WCAG 2.1 A/AA, page chargée, document préparé, règles essayées, annuaire vide, brouillon refusé | 33 critères en partie : 1.1, 3.2, 5.4, 5.7, 6.1, 8.3, 11.1, 11.2, 12.7… | axe-core |
| Titre associé à chaque tableau | 5.4 | Playwright |
| Contour des champs (3:1) et texte d'exemple (4,5:1) | 3.3, 3.2 | mesure maison |
| Messages de statut en `role="status"`, jamais masqués, et annoncés de nouveau quand ils se répètent | 7.5 | mesure maison |
| Page lisible à 320 px de large, puis avec l'espacement du texte agrandi, sans défilement horizontal (hors tableaux et code) | 10.11, 10.12 | mesure maison |
| Lien d'évitement en premier, qui mène au contenu ; indicateur de focus sur chaque élément atteint au clavier | 12.7, 10.7 | Playwright |

La taille des cibles (WCAG 2.2) n'est pas contrôlée : elle ne fait pas partie du RGAA 4.1.2.

## Limites

Ces tests évitent les régressions sur ce qui se mesure. Ils **ne suffisent pas à déclarer la conformité** : la pertinence des intitulés, des titres et des alternatives, la restitution réelle par les lecteurs d'écran (NVDA, JAWS, VoiceOver) et une bonne partie des thèmes 7 à 13 demandent un audit manuel.

## En local

Prérequis : Node.js 24.

```bash
cd accessibilite
npm ci
npx playwright install chromium
npm test
```

Pour éprouver une autre version de l'interface (par exemple une ancienne) : `ADMIN_STATIQUE=/chemin/vers/admin npx playwright test`.

En cas d'échec, la CI joint le rapport Playwright (`rapport-accessibilite`) : il contient les résultats complets d'axe-core de chaque état testé.

## Ajouter une interface

Un fichier `tests/<interface>.spec.js` par interface, sur le modèle de `tests/admin.spec.js` : `servir()` (fichiers statiques et API simulée), `axe()` et les mesures de `tests/outils.js`. Ajouter ses pages HTML au script `html` de `package.json`.
