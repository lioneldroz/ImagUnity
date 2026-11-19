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
  enabled: true, boxId: 'b-1',
  setup: { organization: 'chu-exemple', organizationShortCode: 'F4KL-7KTU-K3RP', directoryUrl: 'https://annuaire.chu-exemple.test',
    codeProvided: true, submittedAt: minutes(-30), attemptAt: minutes(-30), error: null }, shortCode: 'AAAA-BBBB-CCCC', freshness: { issuedAt: minutes(-60), stale: false, error: null },
  lastSync: { at: minutes(-3), accepted: 5, rejected: 1, error: null },
  collisions: [{ boxId: 'b-9', claimant: 'clinique-x', holder: 'chu-exemple', at: minutes(-100) }],
  partners: [{ organization: 'chu-voisin', shortCode: 'MMMM-NNNN', status: 'ACTIVE', validUntil: minutes(60 * 24 * 60),
    localGroups: ['urgences'], partnerGroups: ['*'], relays: ['https://relais.chu-voisin.fr'],
    lastSync: { at: minutes(-4), accepted: 3, rejected: 0, error: null }, boxes: 3, freshness: { issuedAt: minutes(-50), stale: true, error: null } }],
  boxes: [box('b-1', 'Imagerie Nord'), box('b-2', 'Urgences', { pairedAs: 'urgences' }), box('b-5', 'Pédiatrie Sud'),
    box('x-9', 'Neuro (CHU voisin)', { organization: 'chu-voisin', refusal: 'hors du périmètre de l’accord', protocols: ['iu1.0', 'iu1.1'], protocol: 'iu1.1' }),
    box('b-3', 'Ancienne box', { status: 'REVOKED', reachable: false, encrypted: false })],
};

const verif = { node: 'pacs-nord', echoAt: minutes(-3), echoError: null, echoOkAt: minutes(-3) };
const CONFIGURATION = { enabled: true, sequence: 5, issuedAt: minutes(-2000), author: 'Dr Martin', comment: 'nouveau PACS IRM',
  appliedAt: minutes(-1990), priority: true, routing: true, routingRules: ['irm → pacs-irm'], refused: 6,
  refusal: 'nœud « pacs-urg » sans port', checkedAt: minutes(-3), error: null, groups: ['imagerie-nord'],
  dicom: ['nœud pacs-nord PACS_NORD 10.2.0.15:104 (source, destination)'],
  devices: { aet: 'IU_NORD', echoEveryMinutes: 30,
    nodes: [
      { name: 'pacs-nord', aet: 'PACS_NORD', host: '10.2.0.15', port: 104, source: true, destination: true,
        check: { ...verif, receivedAt: minutes(-20) } },
      { name: 'pacs-irm', aet: 'PACS_IRM', host: '10.2.0.16', port: 11112, source: false, destination: true,
        check: { node: 'pacs-irm', echoAt: minutes(-3), echoError: 'association refusée', echoOkAt: minutes(-600) } },
      { name: 'scanner-urg', aet: 'CT_URG', host: '10.2.0.30', port: null, source: true, destination: false,
        check: { node: 'scanner-urg', refusedAt: minutes(-8), refusedFrom: '10.2.0.31' } }],
    outgoing: [{ name: 'urgences-vers-sud', from: ['scanner-urg'], calledAet: 'IU_NORD', filter: 'Modality = CT', to: ['b-2'] }],
    incoming: [{ name: 'irm-du-sud', from: ['b-2'], calledAet: null, filter: 'Modality = MR', to: ['pacs-irm'] },
      { name: 'tout-le-reste', from: [], calledAet: null, filter: null, to: ['pacs-nord'] }] } };

/**
 * Ouvre la console avec l'API simulée, sur une page (#envois…). `appairageRefuse` : la box d'en face ne répond pas ;
 * `enAttente` : la box n'est pas encore enrôlée (pas d'entrée à son nom dans l'annuaire) ; `box` : réponse de /box.
 */
async function ouvrir(page, { appairageRefuse = false, enAttente = false, section = '', box: infos } = {}) {
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
  const annuaire = enAttente ? { ...ANNUAIRE, boxes: ANNUAIRE.boxes.filter((b) => b.boxId !== 'b-1'), collisions: [] } : ANNUAIRE;
  await servir(page, {
    racine: RACINE,
    prefixe: '/',
    api: (chemin) => {
      if (chemin === '/api/v1/box') return { corps: infos || { name: 'box-imagerie-nord', version: '0.15.0', releaseSequence: 12 } };
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
      if (chemin === '/api/v1/directory') return { corps: annuaire };
      if (chemin.endsWith('/pair')) {
        return appairageRefuse ? { statut: 502, corps: { detail: 'Box injoignable : délai dépassé' } } : { statut: 202, corps: {} };
      }
      if (chemin === '/api/v1/configuration') return { corps: CONFIGURATION };
      if (chemin === '/api/v1/priority') {
        return { corps: { source: '/etc/imagunity/priorite.json', loadedAt: minutes(-500), error: null, defaultPriority: 5,
          rules: [{ name: 'thorax', priority: 1, and: { Modality: 'CT', SeriesDescription: '*THORAX*' }, or: {} },
            { name: 'irm', priority: 3, and: {}, or: { Modality: 'MR' } }] } };
      }
      return chemin.startsWith('/api/') ? { statut: 404, corps: {} } : undefined;
    },
  });
  // Une autre page de la console déjà ouverte : rechargement complet, pas un simple changement d'ancre.
  const dejaOuverte = page.url().startsWith(ORIGINE);
  await page.goto(ORIGINE + '/' + section);
  if (dejaOuverte) await page.reload();
  await expect(page.getByRole('status').filter({ hasText: 'En direct' })).toBeVisible();
  await expect(page.locator('h1')).toHaveText(enAttente ? 'Mise en service' : TITRES[section.slice(1) || 'activite']);
  await page.waitForLoadState('networkidle');
}

const TITRES = { activite: 'Activité de la box', envois: 'Envois', receptions: 'Réceptions', partenaires: 'Partenaires',
  configuration: 'Configuration' };
const PAGES = ['#activite', '#envois', '#receptions', '#partenaires', '#configuration'];

/** Ouvre le détail du premier envoi (page des envois). */
async function ouvrirDetail(page) {
  await page.locator('#etude-tr-1').click();
  await expect(page.locator('table.paquets')).toBeVisible();
}

/** Ouvre ce qui se déplie sur la page : détail d'un envoi, d'une réception, d'un appareil en échec. */
async function deplier(page, section) {
  if (section === '#envois') await ouvrirDetail(page);
  if (section === '#receptions') await page.getByRole('button', { name: /2\.25\.888/ }).click();
  if (section === '#configuration') await page.getByRole('button', { name: 'Détail' }).first().click();
}

test.describe('axe-core (critères RGAA détectables automatiquement)', () => {
  for (const section of PAGES) {
    test('page ' + section + ', puis ses détails dépliés', async ({ page }, testInfo) => {
      await ouvrir(page, { section });
      await axe(page, testInfo, section);
      await deplier(page, section);
      await axe(page, testInfo, section + ' déplié');
    });
  }

  test('mise en service, version non vérifiée', async ({ page }, testInfo) => {
    await ouvrir(page, { enAttente: true, box: { name: 'box-imagerie-nord', version: '0.15.1', releaseSequence: null } });
    await expect(page.getByText('AAAA-BBBB-CCCC')).toBeVisible();
    await expect(page.getByText(/ne porte pas de signature valide/)).toBeVisible();
    await axe(page, testInfo, 'mise en service');
  });

  test('appairage refusé', async ({ page }, testInfo) => {
    await ouvrir(page, { appairageRefuse: true, section: '#partenaires' });
    await page.getByRole('button', { name: 'Appairer' }).click();
    await expect(page.locator('form.pair .error')).toBeVisible();
    await axe(page, testInfo, 'appairage refusé');
  });
});

test('12.2 et 12.6 : menu de navigation, page courante signalée, titre de page et focus sur le titre', async ({ page }) => {
  await ouvrir(page);
  const menu = page.getByRole('navigation', { name: 'Sections' });
  await expect(menu.getByRole('link', { name: /^Activité/ })).toHaveAttribute('aria-current', 'page');
  await menu.getByRole('link', { name: /^Envois/ }).focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#envois$/);
  await expect(page.locator('h1')).toHaveText('Envois');
  await expect(page.locator('h1'), 'focus sur le titre de la nouvelle page').toBeFocused();
  await expect(page).toHaveTitle('Envois · box-imagerie-nord');
  await expect(menu.getByRole('link', { name: /^Envois/ })).toHaveAttribute('aria-current', 'page');
  await expect(menu.getByRole('link', { name: /^Activité/ })).not.toHaveAttribute('aria-current', 'page');
  // Pastilles du menu : le nombre se lit avec son sens.
  await expect(menu.getByRole('link', { name: /^Envois/ })).toHaveAccessibleName('Envois 1 en cours');
});

test('12.7 : lien d\'évitement vers le contenu', async ({ page }) => {
  await ouvrir(page);
  await page.keyboard.press('Tab');
  const lien = page.getByRole('link', { name: 'Aller au contenu' });
  await expect(lien).toBeFocused();
  await expect(lien).toBeInViewport();
  await page.keyboard.press('Enter');
  await expect(page.locator('h1'), 'focus sur le titre de la page').toBeFocused();
  await expect(page, 'la page affichée ne change pas').toHaveURL(ORIGINE + '/');
});

test('mise en service : seule section ouverte, les autres signalées indisponibles', async ({ page }) => {
  await ouvrir(page, { enAttente: true, section: '#envois' });
  const menu = page.getByRole('navigation', { name: 'Sections' });
  await expect(menu.getByRole('link')).toHaveCount(1);
  await expect(menu.getByText('Envois')).toHaveAttribute('aria-disabled', 'true');
  await expect(page.getByText('Demande d’enrôlement envoyée')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Télécharger la demande d’enrôlement' })).toBeVisible();
});

test('7.1 et 7.3 : le détail d\'un envoi s\'ouvre et se ferme au clavier', async ({ page }) => {
  await ouvrir(page, { section: '#envois' });
  const etude = page.locator('#etude-tr-1');
  await etude.focus();
  await expect(etude).toHaveAttribute('aria-expanded', 'false');
  await page.keyboard.press('Enter');
  await expect(etude).toHaveAttribute('aria-expanded', 'true');
  await expect(page.locator('#' + await etude.getAttribute('aria-controls')).locator('table')).toBeVisible();
  await expect(page.locator('#paquets-tr-1 .parcours'), 'parcours de l\'envoi').toContainText('Déposé (en échec)');
  await page.keyboard.press('Space');
  await expect(etude).toHaveAttribute('aria-expanded', 'false');
  await expect(page.locator('#paquets-tr-1')).toHaveCount(0);
});

test('7.1 et 7.3 : le détail d\'un appareil en échec se déplie au clavier', async ({ page }) => {
  await ouvrir(page, { section: '#configuration' });
  const carte = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: 'pacs-irm' }) });
  await expect(carte.getByText('C-ECHO en échec il y a 3 min')).toBeVisible();
  const bouton = carte.getByRole('button', { name: 'Détail' });
  await bouton.focus();
  await page.keyboard.press('Enter');
  await expect(carte.getByRole('button', { name: 'Masquer' })).toHaveAttribute('aria-expanded', 'true');
  await expect(carte.getByText(/Association refusée\. Vérifiez 10\.2\.0\.16:11112/)).toBeVisible();
  await page.keyboard.press('Enter');
  await expect(carte.getByRole('button', { name: 'Détail' })).toHaveAttribute('aria-expanded', 'false');
});

test('filtres : boutons pressés, avec leur nombre', async ({ page }) => {
  await ouvrir(page, { section: '#envois' });
  const filtres = page.getByRole('group', { name: 'Filtrer par état' });
  await expect(filtres.getByRole('button', { name: /^Tous/ })).toHaveAttribute('aria-pressed', 'true');
  await filtres.getByRole('button', { name: /^En échec/ }).click();
  await expect(filtres.getByRole('button', { name: /^En échec/ })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('table.liste > tbody > tr')).toHaveCount(1);
  await page.getByRole('searchbox', { name: 'Rechercher' }).fill('neuro');
  await expect(page.getByText('Aucun envoi ne correspond.')).toBeVisible();
});

test('7.3 : le focus survit à la relance et à l\'appairage', async ({ page }) => {
  await ouvrir(page, { section: '#envois' });
  await page.locator('tr', { has: page.locator('#etude-tr-1') }).getByRole('button', { name: 'Relancer' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('tr', { has: page.locator('#etude-tr-1') }).getByRole('button', { name: 'Relancer' }), 'bouton retiré')
    .toHaveCount(0);
  await expect(page.locator('#etude-tr-1'), 'focus rendu à l\'étude de la ligne').toBeFocused();

  await page.goto(ORIGINE + '/#partenaires');
  await page.getByRole('button', { name: 'Appairer' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('connexion en cours, vérification…')).toBeVisible();
  expect(await page.evaluate(() => document.activeElement !== document.body), 'focus perdu après l\'appairage').toBe(true);
  await expect(page.locator(':focus')).toContainText('connexion en cours');
});

test('7.5 : états et résultats annoncés, y compris un message répété', async ({ page }) => {
  await ouvrir(page, { appairageRefuse: true, section: '#partenaires' });
  await expect(page.locator('.bandeau [role=status]'), 'état du flux en direct').toHaveText('En direct');
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

test('11.1 et 3.3 : champs avec étiquette visible et contour contrasté', async ({ page }) => {
  for (const section of ['#partenaires', '#envois']) {
    await ouvrir(page, { section });
    for (const e of await page.locator('input, select, textarea').all()) {
      expect(await e.evaluate((el) => el.labels.length > 0 && el.labels[0].innerText.trim().length > 0), '11.1 : étiquette visible').toBe(true);
      const c = await e.evaluate((el) => {
        const fond = (x) => { for (; x; x = x.parentElement) { const b = getComputedStyle(x).backgroundColor; if (!/rgba\(0, 0, 0, 0\)|transparent/.test(b)) return b; } return 'rgb(255, 255, 255)'; };
        return { nom: el.getAttribute('aria-label') || el.labels[0].innerText, bordure: getComputedStyle(el).borderTopColor, fond: fond(el), fondParent: fond(el.parentElement) };
      });
      expect(contraste(c.bordure, c.fond), '3.3 : contour de « ' + c.nom + ' »').toBeGreaterThanOrEqual(3);
      expect(contraste(c.bordure, c.fondParent), '3.3 : contour de « ' + c.nom + ' » sur le fond autour').toBeGreaterThanOrEqual(3);
    }
  }
});

test('5.4 et 5.7 : tableaux titrés, en-têtes déclarés et nommés', async ({ page }) => {
  let total = 0;
  for (const section of ['#envois', '#receptions', '#partenaires']) {
    await ouvrir(page, { section });
    await deplier(page, section);
    for (const tableau of await page.locator('table').all()) {
      await expect(tableau, '5.4 : titre du tableau').toHaveAccessibleName(/\S/);
    }
    const enTetes = await page.locator('th').evaluateAll((l) => l.map((th) => ({ scope: th.scope, texte: th.textContent.trim() })));
    total += enTetes.length;
    expect(enTetes.filter((th) => !th.scope), '5.7 : en-têtes sans scope').toEqual([]);
    expect(enTetes.filter((th) => !th.texte), '5.7 : en-têtes vides').toEqual([]);
  }
  expect(total).toBeGreaterThan(20);
});

for (const [titre, style] of [['10.11 : lisible à 320 px de large', null],
  ['10.12 : lisible à 320 px avec l\'espacement du texte agrandi', ESPACEMENT_10_12]]) {
  for (const section of [...PAGES, 'mise en service']) {
    test(titre + ', sans défilement horizontal : ' + section, async ({ page }) => {
      await page.setViewportSize({ width: 320, height: 800 });
      if (section === 'mise en service') await ouvrir(page, { enAttente: true });
      else {
        await ouvrir(page, { section });
        await deplier(page, section);
      }
      if (style) await page.addStyleTag({ content: style });
      // Tableaux : défilement dans leur zone (exception admise par le critère).
      expect(await debordements(page, ['.table-scroll']), 'éléments hors de la largeur de la fenêtre').toEqual([]);
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320);
    });
  }
}

test('10.7 : focus visible sur chaque élément atteint au clavier', async ({ page }) => {
  for (const section of ['#envois', '#configuration', '#partenaires']) {
    await ouvrir(page, { section });
    let atteints = 0;
    for (let i = 0; i < 80; i++) {
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
      expect(f.visible, '10.7 : indicateur de focus de ' + f.nom + ' (' + section + ')').toBe(true);
    }
    expect(atteints, 'éléments atteints au clavier').toBeGreaterThan(6);
  }
});

test.describe('thème clair seulement', () => {
  test.use({ colorScheme: 'dark' });
  test("système réglé en sombre : la console de la box reste claire", async ({ page }) => {
    await ouvrir(page);
    expect(await page.evaluate(() => matchMedia('(prefers-color-scheme: dark)').matches)).toBe(true);
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe('rgb(230, 242, 239)');
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme)).toBe('light only');
  });
});

test('serveur de la box injoignable : signalé en quelques secondes, sans annoncer que tout fonctionne', async ({ page }) => {
  await page.addInitScript(() => {
    window.EventSource = class extends EventTarget {
      constructor() { super(); this.readyState = 2; setTimeout(() => this.onerror && this.onerror(), 10); }
      close() {}
    };
    window.EventSource.CLOSED = 2;
  });
  await servir(page, { racine: RACINE, prefixe: '/', api: (c) => (c.startsWith('/api/') ? { statut: 502, corps: {} } : undefined) });
  await page.goto(ORIGINE + '/');
  await expect(page.getByText('Le serveur de la box ne répond pas (HTTP 502)', { exact: false })).toBeVisible({ timeout: 5000 });
  await expect(page.locator('.bandeau [role=status]')).toHaveText('Hors ligne, reconnexion…');
  await expect(page.getByText('tout fonctionne', { exact: false })).toHaveCount(0);
});
