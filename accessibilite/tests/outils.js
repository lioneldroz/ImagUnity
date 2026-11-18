// Outils communs : service des fichiers statiques avec une API simulée, axe-core limité au RGAA, mesures maison.
const fs = require('fs');
const path = require('path');
const { expect } = require('@playwright/test');

const AXE = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
const ORIGINE = 'http://imagunity.test';
const TYPES = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png' };

/**
 * Sert `racine` (fichiers statiques) sous `prefixe` et répond aux appels d'API par `api(chemin, requête)`, qui
 * renvoie { statut, corps } ou undefined (404). Aucun service Java n'est démarré.
 */
async function servir(page, { racine, prefixe, api }) {
  await page.route(ORIGINE + '/**', async (route) => {
    const requete = route.request();
    const chemin = new URL(requete.url()).pathname;
    const reponse = await api(chemin, requete);
    if (reponse) {
      return route.fulfill({ status: reponse.statut || 200, contentType: 'application/json',
        body: JSON.stringify(reponse.corps) });
    }
    if (chemin.startsWith(prefixe)) {
      const fichier = path.join(racine, chemin.slice(prefixe.length) || 'index.html');
      if (fichier.startsWith(racine) && fs.existsSync(fichier) && fs.statSync(fichier).isFile()) {
        return route.fulfill({ status: 200, contentType: TYPES[path.extname(fichier)] || 'application/octet-stream',
          body: fs.readFileSync(fichier) });
      }
    }
    return route.fulfill({ status: 404, body: '' });
  });
}

/**
 * Lance axe-core sur la page, limité aux règles rattachées au RGAA 4 (étiquette RGAAv4) et à WCAG 2.1 A/AA, dont le
 * RGAA 4.1.2 est la transposition. Les règles WCAG 2.2 (taille des cibles…) n'en font pas partie.
 */
async function axe(page, testInfo, etat) {
  if (!(await page.evaluate(() => 'axe' in window))) await page.addScriptTag({ content: AXE });
  const resultat = await page.evaluate(() => window.axe.run(document, {
    runOnly: { type: 'tag', values: ['RGAAv4', 'wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] },
  }));
  await testInfo.attach('axe — ' + etat, { body: JSON.stringify(resultat, null, 2), contentType: 'application/json' });
  const violations = resultat.violations.map((v) => ({
    regle: v.id,
    criteres: v.tags.filter((t) => t.startsWith('RGAA-')).join(', ') || v.tags.filter((t) => /^wcag\d/.test(t)).join(', '),
    aide: v.help,
    elements: v.nodes.map((n) => n.target.join(' ')),
  }));
  expect(violations, 'axe-core (' + etat + ')').toEqual([]);
}

/** Taux de contraste WCAG entre deux couleurs CSS calculées (rgb/rgba opaques). */
function contraste(a, b) {
  const lum = (c) => {
    const [r, g, bl] = c.match(/[\d.]+/g).slice(0, 3).map((v) => {
      const x = Number(v) / 255;
      return x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [clair, fonce] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (clair + 0.05) / (fonce + 0.05);
}

/** Couleurs calculées utiles au contraste d'un élément : bordure, fond propre et fond de son conteneur. */
async function couleurs(page, selecteur) {
  return page.$eval(selecteur, (e) => {
    const fond = (el) => {
      for (; el; el = el.parentElement) {
        const c = getComputedStyle(el).backgroundColor;
        if (!/rgba\(0, 0, 0, 0\)|transparent/.test(c)) return c;
      }
      return 'rgb(255, 255, 255)';
    };
    const s = getComputedStyle(e);
    return { bordure: s.borderTopColor, fond: fond(e), fondParent: fond(e.parentElement),
      texteExemple: getComputedStyle(e, '::placeholder').color };
  });
}

/** Espacement du texte du critère 10.12 (WCAG 1.4.12). */
const ESPACEMENT_10_12 = '* { line-height: 1.5 !important; letter-spacing: .12em !important; '
  + 'word-spacing: .16em !important; } p { margin-bottom: 2em !important; }';

/** Éléments qui dépassent la largeur de la fenêtre, hors zones à défilement propre (tableaux, code). */
async function debordements(page, zonesADefilement) {
  return page.evaluate((zones) => {
    const largeur = document.documentElement.clientWidth;
    return [...document.querySelectorAll('body *')]
      .filter((e) => !zones.some((z) => e.closest(z)))
      .filter((e) => { const r = e.getBoundingClientRect(); return r.width > 0 && r.right > largeur + 1; })
      .map((e) => e.tagName.toLowerCase() + (e.id ? '#' + e.id : ''));
  }, zonesADefilement);
}

module.exports = { ORIGINE, servir, axe, contraste, couleurs, ESPACEMENT_10_12, debordements };
