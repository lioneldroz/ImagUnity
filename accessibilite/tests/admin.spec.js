// Interface d'administration du service d'annuaire (/admin/) : contrôles automatiques du RGAA 4.1.2.
// L'API est simulée ; les fichiers sont ceux du dépôt (ou ceux de ADMIN_STATIQUE, pour éprouver une autre version).
const path = require('path');
const { test, expect } = require('@playwright/test');
const { ORIGINE, servir, axe, contraste, couleurs, ESPACEMENT_10_12, debordements } = require('./outils');

const RACINE = path.resolve(process.env.ADMIN_STATIQUE
  || path.join(__dirname, '../../backend/directory-server/src/main/resources/static/admin'));

const ETAT = {
  organization: 'chu-exemple', nextSequence: 4, groups: ['nord', 'urgences'],
  boxes: [
    { boxId: 'box-a-7f3c2e9d', displayName: 'Box radiologie A', groups: ['nord'], status: 'ACTIVE', applied: 3,
      appliedAt: '2026-11-17T08:00:00Z', refused: null, refusal: null, reportedAt: '2026-11-17T08:05:00Z' },
    { boxId: 'box-b-91aa04c1', displayName: 'Box urgences B', groups: ['urgences'], status: 'REVOKED', applied: 0,
      appliedAt: null, refused: 2, refusal: 'signature du document invalide', reportedAt: '2026-11-10T10:00:00Z' },
    { boxId: 'box-c-5d02be77', displayName: 'Box imagerie C', groups: [], status: 'NOT_YET_VALID', applied: null,
      appliedAt: null, refused: null, refusal: null, reportedAt: null },
  ],
  documents: [
    { sequence: 3, issuedAt: '2026-11-16T09:00:00Z', target: { all: false, boxes: ['box-a-7f3c2e9d'], groups: ['nord'] },
      author: 'Dr Martin', comment: 'scanner thorax prioritaire',
      priority: { default: 5, rules: [{ name: 'thorax', priority: 1, and: { Modality: 'CT' } }] }, routing: null,
      dicom: { nodes: [{ name: 'pacs-nord' }], outgoing: [], incoming: [{ name: 'tout' }] } },
  ],
};
const DEMANDES = [
  { boxId: 'box-d-0c1d', displayName: 'Box D', fingerprint: 'AB:CD:EF:01:23:45:67:89:AB:CD:EF:01', withCode: true,
    receivedAt: '2026-11-17T12:00:00Z' },
  { boxId: 'box-e-77fe', displayName: 'Box E', fingerprint: '10:32:54:76:98:BA:DC:FE:10:32:54:76', withCode: false,
    receivedAt: '2026-11-17T13:00:00Z' },
];

/** Ouvre /admin/ avec une API simulée. `brouillonRefuse` : le service refuse le brouillon. */
async function ouvrir(page, { etat = ETAT, demandes = DEMANDES, brouillonRefuse = false } = {}) {
  await servir(page, {
    racine: RACINE,
    prefixe: '/admin/',
    api: (chemin) => {
      if (chemin === '/admin/api/state') return { corps: etat };
      if (chemin === '/directory/v1/enrollment-requests') return { corps: demandes };
      if (chemin === '/admin/api/draft') {
        return brouillonRefuse ? { statut: 400, corps: { detail: 'Priorité : règle sans nom' } } : { corps: { sequence: 4 } };
      }
      if (chemin === '/admin/api/trial') {
        return { corps: { priority: 1, priorityRule: 'thorax', routingRule: 'chu-nord', destinations: ['box-b'] } };
      }
      return undefined;
    },
  });
  await page.goto(ORIGINE + '/admin/index.html');
  await expect(page.locator('#organisation')).toHaveText(etat.organization);
}

async function preparerEtEssayer(page) {
  await page.click('#preparer');
  await expect(page.locator('#prepare')).toBeVisible();
  await page.click('#essayer');
  await expect(page.locator('#resultat')).not.toBeEmpty();
}

test.describe('axe-core (critères RGAA détectables automatiquement)', () => {
  test('page chargée, document préparé, règles essayées', async ({ page }, testInfo) => {
    await ouvrir(page);
    await axe(page, testInfo, 'page chargée');
    await page.click('#preparer');
    await expect(page.locator('#prepare')).toBeVisible();
    await axe(page, testInfo, 'document préparé');
    await page.click('#essayer');
    await expect(page.locator('#resultat')).not.toBeEmpty();
    await axe(page, testInfo, 'règles essayées');
  });

  test('annuaire vide et brouillon refusé', async ({ page }, testInfo) => {
    await ouvrir(page, { etat: { ...ETAT, boxes: [], groups: [], documents: [] }, demandes: [], brouillonRefuse: true });
    await page.click('#preparer');
    await expect(page.locator('#message')).toContainText('Brouillon refusé');
    await axe(page, testInfo, 'annuaire vide, brouillon refusé');
  });
});

test('5.4 : chaque tableau de données a un titre associé', async ({ page }) => {
  await ouvrir(page);
  const tableaux = await page.locator('table').all();
  expect(tableaux.length).toBeGreaterThan(0);
  for (const tableau of tableaux) {
    await expect(tableau).toHaveAccessibleName(/\S/);
  }
});

test('3.2 et 3.3 : contour des champs (3:1) et texte d\'exemple (4,5:1) contrastés', async ({ page }) => {
  await ouvrir(page);
  for (const champ of await page.locator('input[type=text], textarea').all()) {
    const id = await champ.getAttribute('id');
    const c = await couleurs(page, '#' + id);
    expect(contraste(c.bordure, c.fond), `3.3 : contour de #${id} sur le fond du champ`).toBeGreaterThanOrEqual(3);
    expect(contraste(c.bordure, c.fondParent), `3.3 : contour de #${id} sur le fond autour`).toBeGreaterThanOrEqual(3);
    if (await champ.getAttribute('placeholder')) {
      expect(contraste(c.texteExemple, c.fond), `3.2 : texte d'exemple de #${id}`).toBeGreaterThanOrEqual(4.5);
    }
  }
});

test('7.5 : messages de statut annoncés, y compris un message répété', async ({ page }) => {
  await ouvrir(page, { brouillonRefuse: true });
  const regions = ['#message', '#resultat'];
  const toujoursPresentes = async (moment) => {
    for (const r of regions) {
      const region = page.locator(r);
      await expect(region, `${r} (${moment})`).toHaveAttribute('role', 'status');
      expect(await region.evaluate((e) => e.hidden || getComputedStyle(e).display === 'none'),
        `${r} reste rendue (${moment}) : une région masquée n'est pas annoncée`).toBe(false);
    }
  };
  await toujoursPresentes('au chargement');

  // Même erreur deux fois : la région doit être vidée puis remplie de nouveau pour être annoncée la seconde fois.
  await page.evaluate(() => {
    window.textesMessage = [];
    new MutationObserver(() => window.textesMessage.push(document.querySelector('#message').textContent))
      .observe(document.querySelector('#message'), { childList: true, characterData: true, subtree: true });
  });
  for (let i = 0; i < 2; i++) {
    await page.click('#preparer');
    await expect.poll(() => page.evaluate(() => window.textesMessage.filter((t) => t.includes('refusé')).length))
      .toBe(i + 1);
  }
  const textes = await page.evaluate(() => window.textesMessage);
  expect(textes.slice(textes.findIndex((t) => t.includes('refusé'))), 'vidé entre deux messages identiques')
    .toContain('');
  await toujoursPresentes('après une erreur');

  await page.click('#essayer');
  await expect(page.locator('#resultat')).not.toBeEmpty();
  await toujoursPresentes('après un essai');
});

for (const [titre, style] of [['10.11 : lisible à 320 px de large', null],
  ['10.12 : lisible à 320 px avec l\'espacement du texte agrandi', ESPACEMENT_10_12]]) {
  test(titre + ', sans défilement horizontal', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 800 });
    await ouvrir(page);
    await preparerEtEssayer(page);
    if (style) await page.addStyleTag({ content: style });
    // Les tableaux de données et le code défilent dans leur propre zone (exception admise par le critère).
    expect(await debordements(page, ['.tableau', 'pre']), 'éléments hors de la largeur de la fenêtre').toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);
  });
}

test('10.7 et 12.7 : lien d\'évitement en premier, focus toujours visible', async ({ page }) => {
  await ouvrir(page);
  await page.keyboard.press('Tab');
  const lien = page.locator(':focus');
  await expect(lien, '12.7 : premier élément du parcours au clavier').toHaveAttribute('href', '#contenu');
  await expect(lien).toBeInViewport();
  await page.keyboard.press('Enter');
  await expect(page.locator('#contenu'), '12.7 : le lien mène au contenu principal').toBeFocused();

  // 10.7 : chaque élément atteint au clavier garde un indicateur de focus.
  const vus = new Set();
  for (let i = 0; i < 60; i++) {
    await page.keyboard.press('Tab');
    const f = await page.evaluate(() => {
      const e = document.activeElement;
      if (!e || e === document.body) return null;
      const s = getComputedStyle(e);
      return { cle: e.tagName + '#' + e.id + '=' + (e.value || e.textContent).slice(0, 30),
        visible: (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || s.boxShadow !== 'none' };
    });
    if (!f || vus.has(f.cle)) break;
    vus.add(f.cle);
    expect(f.visible, '10.7 : indicateur de focus de ' + f.cle).toBe(true);
  }
  expect(vus.size, 'éléments atteints au clavier').toBeGreaterThan(10);
});
