// Console de la box (React) : contrôles automatiques du RGAA 4.1.2 sur la console construite (frontend/dist).
// L'API et le flux d'événements de la box sont simulés ; CONSOLE_STATIQUE désigne une autre construction à éprouver.
const fs = require('fs');
const path = require('path');
const { test, expect } = require('@playwright/test');
const { ORIGINE, servir, axe, contraste, ESPACEMENT_10_12, debordements } = require('./outils');

const RACINE = path.resolve(process.env.CONSOLE_STATIQUE || path.join(__dirname, '../../frontend/dist'));

const MAINTENANT = new Date('2026-11-18T10:00:00Z');
const minutes = (n) => new Date(MAINTENANT.getTime() + n * 60000).toISOString();

const ENVOIS = [
  { id: 'tr-1', studyInstanceUid: '1.2.826.0.1.3680043.8.498.12345678901234567890', calledAet: 'IU_NORD', destination: 'urgences',
    state: 'FAILED', sizeBytes: 52_000_000, partCount: 3, errorMessage: 'Relais injoignable : connexion refusée',
    createdAt: minutes(-60), updatedAt: minutes(-50), ackOverdue: false },
  { id: 'tr-2', studyInstanceUid: '1.2.826.0.1.3680043.8.498.99887766554433221100', calledAet: 'IU_NORD', destination: 'chu-voisin-neuro',
    state: 'ANNOUNCED', sizeBytes: 1_200_000_000, partCount: 12, errorMessage: null, createdAt: minutes(-300), updatedAt: minutes(-200), ackOverdue: true },
  { id: 'tr-3', studyInstanceUid: '2.25.1234', calledAet: 'IU_NORD', destination: 'urgences', state: 'ACKNOWLEDGED',
    sizeBytes: 8_000_000, partCount: 1, errorMessage: null, createdAt: minutes(-30), updatedAt: minutes(-20), ackOverdue: false },
];
const RECEPTIONS = [
  { transferId: 'in-1', peerName: 'urgences', studyInstanceUid: '1.2.826.0.1.3680043.8.498.55555', partCount: 4, state: 'RESTORED',
    errorMessage: null, restoredInstances: 412, restoredParts: 4, ackSent: true, createdAt: minutes(-90), updatedAt: minutes(-80) },
  { transferId: 'in-2', peerName: 'chu-voisin-neuro', studyInstanceUid: '2.25.777', partCount: 6, state: 'ANNOUNCED', errorMessage: null,
    restoredInstances: 120, restoredParts: 2, ackSent: false, createdAt: minutes(-10), updatedAt: minutes(-5) },
  { transferId: 'in-3', peerName: 'urgences', studyInstanceUid: '2.25.888', partCount: 2, state: 'FAILED',
    errorMessage: 'PACS injoignable (association refusée)', restoredInstances: 0, restoredParts: 0, ackSent: false,
    createdAt: minutes(-15), updatedAt: minutes(-14) },
];
const box = (boxId, displayName, extra) => ({ boxId, displayName, serial: 1, status: 'ACTIVE', validUntil: minutes(60 * 24 * 60),
  reachable: true, pairedAs: null, encrypted: true, organization: null, refusal: null, ...extra });
const ANNUAIRE = {
  enabled: true, boxId: 'b-1', shortCode: 'AAAA-BBBB-CCCC', freshness: { issuedAt: minutes(-60), stale: false, error: null },
  lastSync: { at: minutes(-3), accepted: 5, rejected: 1, error: null },
  collisions: [{ boxId: 'b-9', claimant: 'clinique-x', holder: 'chu-exemple', at: minutes(-100) }],
  partners: [{ organization: 'chu-voisin', shortCode: 'MMMM-NNNN', status: 'ACTIVE', validUntil: minutes(60 * 24 * 60),
    localGroups: ['urgences'], partnerGroups: ['*'], relays: ['https://relais.chu-voisin.fr'],
    lastSync: { at: minutes(-4), accepted: 3, rejected: 0, error: null }, boxes: 3, freshness: { issuedAt: minutes(-50), stale: true, error: null } }],
  boxes: [box('b-1', 'Imagerie Nord'), box('b-2', 'Urgences', { pairedAs: 'urgences' }), box('b-5', 'Pédiatrie Sud'),
    box('x-9', 'Neuro (CHU voisin)', { organization: 'chu-voisin', refusal: 'hors du périmètre de l’accord', protocols: ['iu1.0', 'iu1.1'], protocol: 'iu1.1' }),
    box('b-3', 'Ancienne box', { status: 'REVOKED', reachable: false, encrypted: false })],
};

/** Ouvre la console avec l'API simulée. `appairageRefuse` : la box d'en face ne répond pas. */
async function ouvrir(page, { appairageRefuse = false } = {}) {
  if (!fs.existsSync(path.join(RACINE, 'index.html'))) {
    throw new Error('Console non construite : lancer « npm ci && npm run build » dans frontend/');
  }
  const etat = { relance: false };
  await page.clock.setFixedTime(MAINTENANT);
  // Flux d'événements simulé : prêt, puis ouvert sans fin.
  await page.addInitScript(() => {
    window.EventSource = class extends EventTarget {
      constructor() { super(); this.readyState = 1; setTimeout(() => this.dispatchEvent(new Event('ready')), 10); }
      close() { this.readyState = 2; }
    };
    window.EventSource.CLOSED = 2;
  });
  await servir(page, {
    racine: RACINE,
    prefixe: '/',
    api: (chemin, requete) => {
      if (chemin === '/api/v1/box') return { corps: { name: 'box-imagerie-nord', version: '0.15.0', releaseSequence: 12 } };
      if (chemin === '/api/v1/overview') {
        return { corps: { outbound: { RECEIVED: 1, ANNOUNCED: 2, ACKNOWLEDGED: 10, FAILED: 1 }, inbound: { RESTORED: 5, FAILED: 1 }, ackOverdue: 1, peers: 2 } };
      }
      if (chemin === '/api/v1/transfers') {
        return { corps: etat.relance ? ENVOIS.map((t) => (t.id === 'tr-1' ? { ...t, state: 'RECEIVED', errorMessage: null } : t)) : ENVOIS };
      }
      if (chemin.endsWith('/retry')) {
        etat.relance = true;
        return { corps: { ...ENVOIS[0], state: 'RECEIVED' } };
      }
      if (chemin.startsWith('/api/v1/transfers/')) {
        const t = ENVOIS.find((x) => chemin.endsWith('/' + x.id));
        return { corps: { transfer: t, parts: [1, 2, 3].map((i) => ({ index: i, count: 3, fileName: `${t.id}-${i}.iu`, size: 17_000_000,
          sha256: 'ab'.repeat(32), handedOver: i < 3, uploaded: i < 2 })) } };
      }
      if (chemin === '/api/v1/inbound-transfers') return { corps: RECEPTIONS };
      if (chemin === '/api/v1/peers') {
        return { corps: [{ name: 'urgences', displayName: 'Urgences', pairedAt: minutes(-10000), boxId: 'b-2', directoryStatus: 'ACTIVE' },
          { name: 'labo', displayName: 'Laboratoire', pairedAt: minutes(-5000), boxId: null, directoryStatus: null }] };
      }
      if (chemin === '/api/v1/directory') return { corps: ANNUAIRE };
      if (chemin.endsWith('/pair')) {
        return appairageRefuse ? { statut: 502, corps: { detail: 'Box injoignable : délai dépassé' } } : { statut: 202, corps: {} };
      }
      if (chemin === '/api/v1/configuration') {
        return { corps: { enabled: true, sequence: 5, issuedAt: minutes(-2000), author: 'Dr Martin', comment: 'nouveau PACS IRM',
          appliedAt: minutes(-1990), priority: true, routing: true, routingRules: ['irm → pacs-irm'], refused: 6,
          refusal: 'nœud « pacs-urg » sans port', checkedAt: minutes(-3), error: null, groups: ['imagerie-nord'],
          dicom: ['nœud pacs-nord PACS_NORD 10.2.0.15:104 (source, destination)'] } };
      }
      if (chemin === '/api/v1/priority') {
        return { corps: { source: '/etc/imagunity/priorite.json', loadedAt: minutes(-500), error: null, defaultPriority: 5,
          rules: [{ name: 'thorax', priority: 1, and: { Modality: 'CT', SeriesDescription: '*THORAX*' }, or: {} },
            { name: 'irm', priority: 3, and: {}, or: { Modality: 'MR' } }] } };
      }
      return chemin.startsWith('/api/') ? { statut: 404, corps: {} } : undefined;
    },
  });
  await page.goto(ORIGINE + '/');
  await expect(page.getByText('Pédiatrie Sud')).toBeVisible();
  await expect(page.locator('.stream')).toHaveText('en direct');
}

/** Ouvre le détail du premier envoi d'un clic sur sa ligne (la souris garde ce geste). */
async function ouvrirDetail(page) {
  await page.locator('tr.clickable').first().locator('td').nth(2).click();
  await expect(page.locator('table.parts')).toBeVisible();
}

test.describe('axe-core (critères RGAA détectables automatiquement)', () => {
  test('console chargée, puis détail d\'un envoi ouvert', async ({ page }, testInfo) => {
    await ouvrir(page);
    await axe(page, testInfo, 'chargée');
    await ouvrirDetail(page);
    await axe(page, testInfo, 'détail ouvert');
  });

  test('appairage refusé', async ({ page }, testInfo) => {
    await ouvrir(page, { appairageRefuse: true });
    await page.getByRole('button', { name: 'Appairer' }).click();
    await expect(page.locator('form.pair .error')).toBeVisible();
    await axe(page, testInfo, 'appairage refusé');
  });
});

test('7.1 et 7.3 : le détail d\'un envoi s\'ouvre et se ferme au clavier', async ({ page }) => {
  await ouvrir(page);
  const etude = page.locator('#etude-tr-1');
  await etude.focus();
  await expect(etude).toHaveAttribute('aria-expanded', 'false');
  await page.keyboard.press('Enter');
  await expect(etude).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('#' + await etude.getAttribute('aria-controls')).locator('table')).toBeVisible();
  await page.keyboard.press('Space');
  await expect(etude).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('#paquets-tr-1')).toHaveCount(0);
});

test('7.3 : le focus survit à la relance et à l\'appairage', async ({ page }) => {
  await ouvrir(page);
  await page.locator('tr', { has: page.locator('#etude-tr-1') }).getByRole('button', { name: 'Relancer' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('tr', { has: page.locator('#etude-tr-1') }).getByRole('button', { name: 'Relancer' }), 'bouton retiré')
    .toHaveCount(0);
  await expect(page.locator('#etude-tr-1'), 'focus rendu à l\'étude de la ligne').toBeFocused();

  await page.getByRole('button', { name: 'Appairer' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('connexion en cours, vérification…')).toBeVisible();
  expect(await page.evaluate(() => document.activeElement !== document.body), 'focus perdu après l\'appairage').toBe(true);
  await expect(page.locator(':focus')).toContainText('connexion en cours');
});

test('7.5 : états et résultats annoncés, y compris un message répété', async ({ page }) => {
  await ouvrir(page, { appairageRefuse: true });
  await expect(page.locator('.stream'), 'état du flux en direct').toHaveAttribute('role', 'status');
  const annonces = page.locator('#annonces');
  await expect(annonces).toHaveAttribute('role', 'status');
  await page.evaluate(() => {
    window.textes = [];
    new MutationObserver(() => window.textes.push(document.querySelector('#annonces').textContent))
      .observe(document.querySelector('#annonces'), { childList: true, characterData: true, subtree: true });
  });
  for (let i = 0; i < 2; i++) {
    await page.getByRole('button', { name: 'Appairer' }).click();
    await expect.poll(() => page.evaluate(() => window.textes.filter((t) => t.includes('impossible')).length)).toBe(i + 1);
  }
  const textes = await page.evaluate(() => window.textes);
  expect(textes.slice(textes.findIndex((t) => t.includes('impossible'))), 'vidée entre deux messages identiques').toContain('');
  await expect(annonces).toContainText('Appairage avec Pédiatrie Sud impossible : Box injoignable');
  expect(await annonces.evaluate((e) => e.hidden || getComputedStyle(e).display === 'none'), 'région toujours rendue').toBe(false);
});

test('11.1 et 3.3 : champ d\'appairage avec étiquette visible et contour contrasté', async ({ page }) => {
  await ouvrir(page);
  const champ = page.getByRole('textbox', { name: 'Nom local pour Pédiatrie Sud' });
  expect(await champ.evaluate((e) => e.labels.length && e.labels[0].innerText.includes('Nom local')), 'étiquette visible').toBeTruthy();
  for (const e of await page.locator('input, select, textarea').all()) {
    const c = await e.evaluate((el) => {
      const fond = (x) => { for (; x; x = x.parentElement) { const b = getComputedStyle(x).backgroundColor; if (!/rgba\(0, 0, 0, 0\)|transparent/.test(b)) return b; } return 'rgb(255, 255, 255)'; };
      return { nom: el.getAttribute('aria-label'), bordure: getComputedStyle(el).borderTopColor, fond: fond(el), fondParent: fond(el.parentElement) };
    });
    expect(contraste(c.bordure, c.fond), '3.3 : contour de « ' + c.nom + ' »').toBeGreaterThanOrEqual(3);
    expect(contraste(c.bordure, c.fondParent), '3.3 : contour de « ' + c.nom + ' » sur le fond autour').toBeGreaterThanOrEqual(3);
  }
});

test('5.4 et 5.7 : tableaux titrés, en-têtes déclarés et nommés', async ({ page }) => {
  await ouvrir(page);
  await ouvrirDetail(page);
  for (const tableau of await page.locator('table').all()) {
    await expect(tableau, '5.4 : titre du tableau').toHaveAccessibleName(/\S/);
  }
  const enTetes = await page.locator('th').evaluateAll((l) => l.map((th) => ({ scope: th.scope, texte: th.textContent.trim() })));
  expect(enTetes.length).toBeGreaterThan(20);
  expect(enTetes.filter((th) => !th.scope), '5.7 : en-têtes sans scope').toEqual([]);
  expect(enTetes.filter((th) => !th.texte), '5.7 : en-têtes vides').toEqual([]);
});

for (const [titre, style] of [['10.11 : lisible à 320 px de large', null],
  ['10.12 : lisible à 320 px avec l\'espacement du texte agrandi', ESPACEMENT_10_12]]) {
  test(titre + ', sans défilement horizontal', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 800 });
    await ouvrir(page);
    await ouvrirDetail(page);
    if (style) await page.addStyleTag({ content: style });
    // Tableaux : défilement dans leur zone (exception admise par le critère).
    expect(await debordements(page, ['.table-scroll']), 'éléments hors de la largeur de la fenêtre').toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);
  });
}

test('10.7 : focus visible sur chaque élément atteint au clavier', async ({ page }) => {
  await ouvrir(page);
  let atteints = 0;
  for (let i = 0; i < 60; i++) {
    await page.keyboard.press('Tab');
    const f = await page.evaluate(() => {
      const e = document.activeElement;
      if (!e || e === document.body || e.hasAttribute('data-deja-vu')) return null;
      e.setAttribute('data-deja-vu', '');
      const s = getComputedStyle(e);
      return { nom: e.tagName + ' « ' + (e.value || e.textContent).trim().slice(0, 30) + ' »',
        visible: (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || s.boxShadow !== 'none' };
    });
    if (!f) break;
    atteints++;
    expect(f.visible, '10.7 : indicateur de focus de ' + f.nom).toBe(true);
  }
  expect(atteints, 'éléments atteints au clavier').toBeGreaterThan(6);
});

test.describe('thème clair seulement', () => {
  test.use({ colorScheme: 'dark' });
  test("système réglé en sombre : la console de la box reste claire", async ({ page }) => {
    await ouvrir(page);
    expect(await page.evaluate(() => matchMedia('(prefers-color-scheme: dark)').matches)).toBe(true);
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe('rgb(246, 247, 249)');
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme)).toBe('light only');
  });
});
