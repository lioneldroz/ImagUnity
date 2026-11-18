// Interface locale du responsable (outil du poste du responsable) : contrôles automatiques du RGAA 4.1.2.
// L'API de l'outil est simulée ; les fichiers sont ceux du dépôt (ou ceux de RESPONSABLE_STATIQUE).
const path = require('path');
const { test, expect } = require('@playwright/test');
const { ORIGINE, servir, axe, contraste, ESPACEMENT_10_12, debordements } = require('./outils');

const RACINE = path.resolve(process.env.RESPONSABLE_STATIQUE
  || path.join(__dirname, '../../backend/directory/src/main/resources/com/imagunity/directory/interface'));

const MAINTENANT = new Date('2026-11-18T10:00:00Z');
const jours = (n) => new Date(MAINTENANT.getTime() + n * 86400000).toISOString();
const ONGLETS = ['tableau', 'demarrage', 'demandes', 'box', 'configuration', 'codes', 'partenaires', 'aide'];

const ETAT = { initialisee: true, organisation: 'chu-exemple', empreinteOrganisation: 'QZYY-WDJT-LDE4-GY7V-5GPV-5RQW',
  annuaire: 'https://annuaire.chu-exemple.fr', validiteJours: 90, phrase: 'requise', compte: 'saisi',
  sauvegarde: { le: jours(-120), modifies: ['codes.json'] }, dossier: 'C:\\ImagUnity\\organisation',
  fichierCle: 'C:\\ImagUnity\\organisation\\organisation.json' };
const DEMARRAGE = { etat: ETAT,
  annuaire: { joignable: true, memeCle: true, organisation: 'chu-exemple', empreinte: ETAT.empreinteOrganisation, fraicheurPubliee: true },
  fraicheur: { cle: 'fraicheur.json', delegation: 'delegation.json', numero: 3, jusquAu: jours(5), expireBientot: true } };
const DEMANDES = [
  { statut: 'code-verifie', nom: 'CHU Exemple — imagerie Nord', nomPropose: 'box-nord', boxId: 'b-5f2a91c0d3',
    empreinte: 'ABCD-EFGH-IJKL', groupes: ['imagerie-nord'], recueLe: jours(-1) },
  { statut: 'sans-code', nomPropose: 'box-urgences', boxId: 'b-77c1e0aa42', empreinte: 'MNOP-QRST-UVWX', recueLe: jours(-2),
    detail: 'aucun code joint' },
  { statut: 'code-expire', nomPropose: 'box-ancienne', boxId: 'b-0912fe33aa', empreinte: 'YZAB-CDEF-GHIJ', recueLe: jours(-3),
    detail: 'code expiré le 01/11/2026' },
];
const BOX = [
  { boxId: 'b-1', nom: 'Imagerie Nord', empreinte: 'AAAA-BBBB-CCCC', statut: 'ACTIVE', groupes: ['imagerie-nord'],
    valideJusquau: jours(6), serie: 4, aRenouveler: true },
  { boxId: 'b-2', nom: 'Urgences', empreinte: 'DDDD-EEEE-FFFF', statut: 'ACTIVE', groupes: ['urgences'], valideJusquau: jours(80),
    serie: 2, aRenouveler: false },
  { boxId: 'b-3', nom: 'Ancienne box', empreinte: 'GGGG-HHHH-IIII', statut: 'REVOKED', groupes: [], valideJusquau: jours(20),
    serie: 5, revoqueeLe: jours(-10), motif: 'remplacée' },
  { boxId: 'b-4', nom: 'Box expirée', empreinte: 'JJJJ-KKKK-LLLL', statut: 'EXPIRED', groupes: [], valideJusquau: jours(-3),
    serie: 1, aRenouveler: false },
];
const CODES = [
  { id: 'c1', nom: 'Neurochirurgie', groupes: ['neuro'], statut: 'libre', expireLe: jours(20), code: 'K7Q2-9XWD-4MZP' },
  { id: 'c2', nom: 'Imagerie Nord', groupes: ['imagerie-nord'], statut: 'utilise', utiliseLe: jours(-30), boxId: 'b-1', expireLe: jours(-5) },
  { id: 'c3', nom: 'Pédiatrie', groupes: [], statut: 'expire', expireLe: jours(-2), code: 'P3RT-77LK-2QWE' },
];
const PARTENAIRES = [
  { organisation: 'chu-voisin', empreinteCourte: 'MMMM-NNNN', empreinte: 'MMMM-NNNN-OOOO-PPPP', annuaire: 'https://annuaire.chu-voisin.fr',
    statut: 'ACTIVE', valideJusquau: jours(60), serie: 2, groupesLocaux: ['urgences'], groupesPartenaire: ['*'],
    relais: ['https://relais.chu-voisin.fr'] },
  { organisation: 'clinique-sud', empreinteCourte: 'QQQQ-RRRR', empreinte: 'QQQQ-RRRR-SSSS', annuaire: 'https://annuaire.clinique-sud.fr',
    statut: 'REVOKED', revoqueeLe: jours(-15), motif: 'fin du partenariat', valideJusquau: jours(10), serie: 3, groupesLocaux: [],
    groupesPartenaire: [], relais: ['https://relais.clinique-sud.fr'] },
];
const CONFIGURATIONS = {
  prochainNumero: 7,
  destinations: [{ boxId: 'b-1', nom: 'Imagerie Nord', organisation: 'chu-exemple' },
    { boxId: 'b-2', nom: 'Urgences', organisation: 'chu-exemple' }, { boxId: 'x-9', nom: 'Neuro', organisation: 'chu-voisin' }],
  box: [
    { boxId: 'b-1', nom: 'Imagerie Nord', groupes: ['imagerie-nord'], numero: 5, appliquee: 5, cible: 'cette box',
      dicom: { aet: 'IU_NORD',
        nodes: [{ name: 'pacs-nord', aet: 'PACS_NORD', host: '10.2.0.15', port: 104, source: true, destination: true },
          { name: 'scanner-1', aet: 'CT_1', host: '10.2.0.40', port: 104, source: true, destination: false }],
        outgoing: [{ name: 'vers-urgences', from: ['scanner-1'], and: { Modality: 'CT' }, or: {}, to: ['b-2'] }],
        incoming: [{ name: 'tout-au-pacs', from: [], and: {}, or: {}, to: ['pacs-nord'] }] },
      appareils: { noeuds: { 'pacs-nord': { echoAt: jours(-0.01), echoError: 'association refusée (AET inconnu)' },
        'scanner-1': { receivedAt: jours(-0.2) } } } },
    { boxId: 'b-2', nom: 'Urgences', groupes: ['urgences'], numero: 6, appliquee: 5, refusee: 6, refus: 'nœud « pacs-urg » sans port',
      cible: 'cette box', dicom: { aet: '', nodes: [], outgoing: [], incoming: [] } },
  ],
};

/** Ouvre l'interface avec l'API de l'outil simulée. `initialisee` : organisation déjà créée sur le poste. */
async function ouvrir(page, { initialisee = true, horloge = false } = {}) {
  if (horloge) await page.clock.install({ time: MAINTENANT });
  else await page.clock.setFixedTime(MAINTENANT);
  await servir(page, {
    racine: RACINE,
    prefixe: '/',
    api: (chemin, requete) => {
      const get = requete.method() === 'GET';
      if (chemin === '/api/etat') return { corps: initialisee ? ETAT : { initialisee: false, annuaire: ETAT.annuaire } };
      if (chemin === '/api/demarrage') {
        return { corps: initialisee ? DEMARRAGE : { etat: { initialisee: false, dossier: ETAT.dossier, annuaire: ETAT.annuaire, compte: 'requis' },
          annuaire: { joignable: false, detail: 'connexion refusée' }, fraicheur: { cle: 'fraicheur.json', delegation: 'delegation.json' } } };
      }
      if (chemin === '/api/demandes') return { corps: DEMANDES };
      if (chemin === '/api/box') return { corps: BOX };
      if (chemin === '/api/codes') {
        return { corps: get ? CODES : { nom: 'Cardiologie', expireLe: jours(30), code: 'Z9Z9-8Y8Y-7X7X',
          ligne: 'IMAGUNITY_DIRECTORY_ENROLLMENT_CODE=Z9Z9-8Y8Y-7X7X', message: 'Code tiré.' } };
      }
      if (chemin === '/api/partenaires' && get) return { corps: PARTENAIRES };
      if (chemin === '/api/partenaires/cle') return { corps: { organisation: 'chu-lointain', empreinteCourte: 'TTTT-UUUU', empreinte: 'TTTT-UUUU-VVVV' } };
      if (chemin === '/api/configurations') return { corps: CONFIGURATIONS };
      if (chemin === '/api/configuration/essai') return { corps: { route: null, rang: null, vers: [] } };
      if (chemin.startsWith('/api/')) return { corps: { message: 'Fait.' } };
      return undefined;
    },
  });
  await page.goto(ORIGINE + '/#jeton=essai');
  await expect(page.locator('#pastille-annuaire')).toBeVisible();
  if (initialisee) await expect(page.locator('#box tr')).toHaveCount(BOX.length);
}

async function onglet(page, nom) {
  await page.click(`[data-onglet="${nom}"]`);
  await expect(page.locator('#onglet-' + nom)).toBeVisible();
}

/** Met chaque onglet dans un état riche : essai lancé, code tiré, clé de partenaire lue. */
async function preparerOnglet(page, nom) {
  if (nom === 'configuration') {
    await page.click('#ajouter-sortante');
    await page.fill('#essai-champs', 'Modality=MR');
    await page.click('#essai-lancer');
    await expect(page.locator('#essai-resultat')).not.toBeEmpty();
  }
  if (nom === 'codes') {
    await page.fill('#code-nom', 'Cardiologie');
    await page.click('#formulaire-code button[type=submit]');
    await expect(page.locator('#nouveau-code')).toBeVisible();
  }
  if (nom === 'partenaires') {
    await page.fill('#partenaire-cle', '{"organization":"chu-lointain"}');
    await page.click('#partenaire-lire');
    await expect(page.locator('#partenaire-verifiee')).toBeEnabled();
  }
}

test.describe('axe-core (critères RGAA détectables automatiquement)', () => {
  test('poste sans organisation : démarrage', async ({ page }, testInfo) => {
    await ouvrir(page, { initialisee: false });
    await axe(page, testInfo, 'sans organisation');
  });

  for (const nom of ONGLETS) {
    test('onglet ' + nom, async ({ page }, testInfo) => {
      await ouvrir(page);
      await onglet(page, nom);
      await preparerOnglet(page, nom);
      await axe(page, testInfo, 'onglet ' + nom);
    });
  }

  test('fenêtres de signature, de révocation et de confirmation', async ({ page }, testInfo) => {
    await ouvrir(page);
    await onglet(page, 'demandes');
    await page.locator('.demande').first().getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByRole('dialog', { name: 'Valider la demande' })).toBeVisible();
    await axe(page, testInfo, 'signature');
    await page.click('#annuler-signer');

    await onglet(page, 'box');
    await page.locator('#box').getByRole('button', { name: 'Révoquer' }).first().click();
    await expect(page.getByRole('dialog', { name: 'Révoquer la box' })).toBeVisible();
    await axe(page, testInfo, 'révocation');
    await page.keyboard.press('Escape');

    await onglet(page, 'demandes');
    await page.locator('.demande').first().getByRole('button', { name: 'Refuser' }).click();
    await expect(page.getByRole('dialog', { name: 'Refuser la demande' })).toBeVisible();
    await axe(page, testInfo, 'confirmation');
  });
});

test('11.1 : chaque champ a un nom, autre que son texte d\'exemple', async ({ page }) => {
  await ouvrir(page);
  for (const nom of ONGLETS) {
    await onglet(page, nom);
    await preparerOnglet(page, nom);
    const sansNom = await page.evaluate(() => [...document.querySelectorAll('input, select, textarea')]
      .filter((e) => e.offsetParent && e.type !== 'hidden')
      .filter((e) => !((e.labels && [...e.labels].some((l) => l.textContent.trim())) || e.getAttribute('aria-label')
        || e.getAttribute('aria-labelledby') || e.title))
      .map((e) => e.id || e.outerHTML.slice(0, 60)));
    expect(sansNom, 'champs sans étiquette (onglet ' + nom + ')').toEqual([]);
  }
});

test('3.2 et 3.3 : contour des champs (3:1) et texte d\'exemple (4,5:1) contrastés', async ({ page }) => {
  await ouvrir(page);
  for (const nom of ONGLETS) {
    await onglet(page, nom);
    for (const champ of await page.locator('input[type=text], input[type=password], input[type=search], input[type=number], textarea, select').all()) {
      if (!(await champ.isVisible())) continue;
      const c = await champ.evaluate((e) => {
        const fond = (el) => { for (; el; el = el.parentElement) { const b = getComputedStyle(el).backgroundColor; if (!/rgba\(0, 0, 0, 0\)|transparent/.test(b)) return b; } return 'rgb(255, 255, 255)'; };
        return { id: e.id || e.getAttribute('aria-label') || e.tagName, bordure: getComputedStyle(e).borderTopColor, fond: fond(e),
          fondParent: fond(e.parentElement), exemple: e.placeholder ? getComputedStyle(e, '::placeholder').color : null };
      });
      expect(contraste(c.bordure, c.fond), `3.3 : contour de ${c.id} (onglet ${nom})`).toBeGreaterThanOrEqual(3);
      expect(contraste(c.bordure, c.fondParent), `3.3 : contour de ${c.id} sur le fond autour (onglet ${nom})`).toBeGreaterThanOrEqual(3);
      if (c.exemple) expect(contraste(c.exemple, c.fond), `3.2 : texte d'exemple de ${c.id}`).toBeGreaterThanOrEqual(4.5);
    }
  }
});

test('3.1 : l\'urgence des tâches est écrite, pas seulement colorée', async ({ page }) => {
  await ouvrir(page);
  for (const li of await page.locator('#afaire li').all()) {
    const repere = li.locator('.urgence, .point');
    if (await repere.evaluate((e) => e.classList.contains('point'))) continue; // tâche ordinaire, sans urgence
    await expect(repere, 'étiquette d\'urgence').toHaveText(/Urgent|Bientôt/);
  }
  await expect(page.locator('#afaire .urgence').first()).toBeVisible();
});

test('5.4 : chaque tableau de données a un titre associé', async ({ page }) => {
  await ouvrir(page);
  for (const nom of ONGLETS) {
    await onglet(page, nom);
    for (const tableau of await page.locator('table').all()) {
      if (await tableau.isVisible()) await expect(tableau).toHaveAccessibleName(/\S/);
    }
  }
});

test('7.1 : fenêtres de dialogue nommées', async ({ page }) => {
  await ouvrir(page);
  await onglet(page, 'box');
  await page.locator('#box').getByRole('button', { name: 'Révoquer' }).first().click();
  await expect(page.locator('#dialogue-signer')).toHaveAccessibleName('Révoquer la box');
  await page.keyboard.press('Escape');
  await expect(page.locator('#box').getByRole('button', { name: 'Révoquer' }).first(), 'focus rendu au bouton').toBeFocused();
});

test('7.3 : l\'actualisation automatique ne fait pas perdre le focus', async ({ page }) => {
  await ouvrir(page, { horloge: true });
  for (const [nom, selecteur] of [['demandes', '.demande button:has-text("Valider"):not([disabled])'], ['tableau', '#afaire button'],
    ['partenaires', '#partenaires button'], ['codes', '#codes button'], ['configuration', '#config-liste button']]) {
    await onglet(page, nom);
    const bouton = page.locator(selecteur).last();
    await bouton.focus();
    const texte = await bouton.textContent();
    // Une actualisation (toutes les 30 s) : attendre qu'elle ait tout redessiné (la configuration est lue en dernier).
    const relue = page.waitForResponse((r) => r.url().endsWith('/api/configurations'));
    await page.clock.runFor(31000);
    await relue;
    await page.waitForTimeout(300);
    expect(await page.evaluate(() => document.activeElement !== document.body), 'focus perdu (' + nom + ')').toBe(true);
    expect(await page.evaluate(() => document.activeElement.textContent), 'même bouton (' + nom + ')').toBe(texte);
  }
});

test('7.5 : messages de statut annoncés, y compris un message répété', async ({ page }) => {
  await ouvrir(page);
  for (const r of ['#message', '#essai-resultat']) {
    await expect(page.locator(r)).toHaveAttribute('role', 'status');
  }
  await page.evaluate(() => {
    window.textes = [];
    new MutationObserver(() => window.textes.push(document.querySelector('#message').textContent))
      .observe(document.querySelector('#message'), { childList: true, characterData: true, subtree: true });
  });
  await onglet(page, 'demarrage');
  for (let i = 0; i < 2; i++) {
    await page.locator('[data-copier="org-empreinte"]').click();
    await expect.poll(() => page.evaluate(() => window.textes.filter((t) => /Cop/.test(t)).length)).toBe(i + 1);
  }
  const textes = await page.evaluate(() => window.textes);
  expect(textes.slice(textes.findIndex((t) => /Cop/.test(t))), 'vidé entre deux messages identiques').toContain('');
  await onglet(page, 'configuration');
  await preparerOnglet(page, 'configuration');
  for (const r of ['#message', '#essai-resultat']) {
    expect(await page.locator(r).evaluate((e) => e.hidden || getComputedStyle(e).display === 'none'),
      r + ' reste rendue : une région masquée n\'est pas annoncée').toBe(false);
  }
});

test('10.13 : une bulle d\'aide se ferme avec Échap', async ({ page }) => {
  await ouvrir(page);
  await onglet(page, 'demarrage');
  const bulle = page.locator('#bulle-empreinte');
  await page.locator('#etape-organisation .info-bouton').first().focus();
  await expect(bulle).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(bulle).toBeHidden();
  await expect(page.locator('#etape-organisation .info-bouton').first(), 'le focus ne bouge pas').toBeFocused();
});

test('11.5 et 11.10 : cases regroupées, champs obligatoires signalés', async ({ page }) => {
  await ouvrir(page);
  await onglet(page, 'configuration');
  for (const liste of await page.locator('.choix-liste').all()) {
    expect(await liste.evaluate((e) => !!(e.closest('fieldset') && e.closest('fieldset').querySelector('legend'))),
      '11.5 : cases d\'une route groupées sous une légende').toBe(true);
  }
  for (const id of await page.locator('[required]').evaluateAll((l) => l.map((e) => e.id))) {
    const signale = await page.locator('#' + id).evaluate((e) => /obligatoire/i.test(e.labels[0].textContent)
      || /Tous les champs sont obligatoires/.test(e.form ? e.form.textContent : ''));
    expect(signale, '11.10 : champ obligatoire #' + id + ' signalé').toBe(true);
  }
});

for (const [titre, style] of [['10.11 : lisible à 320 px de large', null],
  ['10.12 : lisible à 320 px avec l\'espacement du texte agrandi', ESPACEMENT_10_12]]) {
  test(titre + ', dans chaque onglet', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 800 });
    await ouvrir(page);
    if (style) await page.addStyleTag({ content: style });
    for (const nom of ONGLETS) {
      await onglet(page, nom);
      // Tableaux de données : défilement dans leur zone (exception du critère). Bulles : contrôlées ci-dessous.
      expect(await debordements(page, ['.tableau', '.bulle']), 'onglet ' + nom).toEqual([]);
    }
    await onglet(page, 'demarrage');
    await page.locator('#etape-organisation .info-bouton').first().focus();
    const r = await page.locator('#bulle-empreinte').boundingBox();
    expect(r.x, 'bulle d\'aide dans l\'écran (gauche)').toBeGreaterThanOrEqual(0);
    expect(r.x + r.width, 'bulle d\'aide dans l\'écran (droite)').toBeLessThanOrEqual(320);
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

  // 10.7 : du menu jusqu'au bout de l'onglet le plus riche, chaque élément atteint garde un indicateur de focus.
  await onglet(page, 'configuration');
  let atteints = 0;
  for (let i = 0; i < 120; i++) {
    await page.keyboard.press('Tab');
    const f = await page.evaluate(() => {
      const e = document.activeElement;
      if (!e || e === document.body || e.hasAttribute('data-deja-vu')) return null; // fin du parcours
      e.setAttribute('data-deja-vu', '');
      const s = getComputedStyle(e);
      return { nom: e.tagName + (e.id ? '#' + e.id : '') + ' « ' + (e.value || e.textContent).trim().slice(0, 30) + ' »',
        visible: (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || s.boxShadow !== 'none' };
    });
    if (!f) break;
    atteints++;
    expect(f.visible, '10.7 : indicateur de focus de ' + f.nom).toBe(true);
  }
  expect(atteints, 'éléments atteints au clavier').toBeGreaterThan(30);
});
