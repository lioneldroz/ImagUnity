"use strict";
// Interface locale du responsable : la page ne parle qu'à l'outil qui l'a servie (127.0.0.1), qui signe sur ce poste.

const jeton = (() => {
  const trouve = /(?:^#|&)jeton=([A-Za-z0-9_-]+)/.exec(location.hash);
  if (trouve) {
    sessionStorage.setItem("jeton", trouve[1]);
    history.replaceState(null, "", location.pathname);
  }
  return sessionStorage.getItem("jeton");
})();

const $ = (id) => document.getElementById(id);
let etat = null;
let signature = null;
let confirmation = null;

async function api(methode, chemin, corps) {
  const reponse = await fetch(chemin, {
    method: methode,
    headers: Object.assign({ "X-Jeton": jeton || "" }, corps === undefined ? {} : { "Content-Type": "application/json" }),
    body: corps === undefined ? undefined : JSON.stringify(corps),
  });
  const donnees = await reponse.json().catch(() => ({}));
  if (!reponse.ok) {
    throw new Error(donnees.erreur || "Erreur HTTP " + reponse.status);
  }
  return donnees;
}

function message(texte, genre) {
  const m = $("message");
  m.textContent = texte;
  m.className = "message " + (genre || "");
  m.hidden = !texte;
}

function element(nom, classe, texte) {
  const e = document.createElement(nom);
  if (classe) e.className = classe;
  if (texte !== undefined && texte !== null) e.textContent = texte;
  return e;
}

function bouton(texte, classe, action) {
  const b = element("button", classe, texte);
  b.type = "button";
  b.addEventListener("click", action);
  return b;
}

function champ(libelle, valeur, options) {
  const input = element("input");
  input.type = (options && options.type) || "text";
  input.value = valeur || "";
  if (options && options.placeholder) input.placeholder = options.placeholder;
  if (options && options.maxLength) input.maxLength = options.maxLength;
  const label = element("label", null, libelle);
  label.append(input);
  return { label, input };
}

const jour = (iso) => new Date(iso).toLocaleDateString("fr-FR");
const listeGroupes = (texte) => texte.split(",").map((g) => g.trim()).filter(Boolean);

// --- En-tête et onglets ---

function afficherEtat() {
  document.querySelectorAll("[data-onglet]").forEach((b) => {
    b.disabled = !etat.initialisee && !["demarrage", "aide"].includes(b.dataset.onglet);
  });
  if (!etat.initialisee) {
    $("contexte").textContent = "Aucune organisation sur ce poste pour l'instant : commencez par l'onglet « Démarrage » — annuaire " + etat.annuaire;
    $("etat-phrase").textContent = "";
    $("oublier").hidden = true;
    return;
  }
  $("contexte").textContent = "Organisation " + etat.organisation + " (empreinte " + etat.empreinteOrganisation
    + ") — annuaire " + etat.annuaire + " — entrées signées pour " + etat.validiteJours + " jours";
  const textes = {
    inutile: "Clé non protégée par phrase de passe (déconseillé)",
    environnement: "Phrase de passe fournie par l'environnement",
    gardee: "Clé déverrouillée pour cette session",
    requise: "Phrase de passe demandée à chaque signature",
  };
  $("etat-phrase").textContent = textes[etat.phrase] || "";
  $("oublier").hidden = etat.phrase !== "gardee";
}

function ongletCourant() {
  const b = document.querySelector("[data-onglet][aria-selected=true]");
  return b ? b.dataset.onglet : "demarrage";
}

function ongletActif(nom) {
  document.querySelectorAll("[data-onglet]").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.onglet === nom)));
  ["demarrage", "demandes", "box", "codes", "configuration", "partenaires", "aide"].forEach((o) => { $("onglet-" + o).hidden = o !== nom; });
  try { sessionStorage.setItem("onglet", nom); } catch (e) { /* sans stockage : onglet non retenu */ }
}

// --- Fenêtre de signature (valider, renouveler, révoquer) ---

/** Demande la phrase de passe si besoin, puis lance action(phrase, garder) ; la fenêtre reste ouverte si la phrase est fausse. */
function signer({ titre, resume, libelle, danger, extra, action }) {
  signature = action;
  $("signer-titre").textContent = titre;
  $("signer-resume").textContent = resume;
  $("signer-extra").replaceChildren(...(extra ? [extra] : []));
  const phrase = etat.phrase === "requise";
  $("bloc-phrase").hidden = !phrase;
  $("bloc-garder").hidden = !phrase;
  $("phrase").value = "";
  $("phrase").required = phrase;
  $("garder").checked = false;
  $("confirmer-signer").textContent = libelle;
  $("confirmer-signer").className = danger ? "danger" : "";
  $("dialogue-signer").showModal();
  const premier = $("signer-extra").querySelector("input") || (phrase ? $("phrase") : null);
  if (premier) premier.focus();
}

async function soumettreSignature(evenement) {
  evenement.preventDefault();
  $("confirmer-signer").disabled = true;
  try {
    const r = await signature($("phrase").value, $("garder").checked);
    $("phrase").value = "";
    $("dialogue-signer").close();
    message(r.message, "ok");
    await charger();
  } catch (e) {
    $("phrase").value = "";
    if (!/phrase de passe|recopier|motif/i.test(e.message)) $("dialogue-signer").close();
    message(e.message, "erreur");
  } finally {
    $("confirmer-signer").disabled = false;
  }
}

/** Confirmation simple, sans signature (refus d'une demande, retrait d'un code). */
function confirmer({ titre, resume, detail, libelle, action }) {
  confirmation = action;
  $("confirmer-titre").textContent = titre;
  $("confirmer-resume").textContent = resume;
  $("confirmer-detail").textContent = detail || "";
  $("confirmer-oui").textContent = libelle;
  $("dialogue-confirmer").showModal();
}

async function soumettreConfirmation() {
  try {
    const r = await confirmation();
    message(r.message, "ok");
  } catch (e) {
    message(e.message, "erreur");
  }
  $("dialogue-confirmer").close();
  await charger();
}

// --- Demandes en attente ---

const STATUTS = {
  "code-verifie": ["Code d'enrôlement vérifié", "ok"],
  "sans-code": ["Sans code : empreinte à vérifier", "attention"],
  "preuve-invalide": ["Preuve du code invalide", "refus"],
  "code-utilise": ["Code déjà utilisé", "refus"],
  "code-expire": ["Code expiré", "refus"],
  "box-revoquee": ["Box révoquée", "refus"],
  "identifiant-pris": ["Identifiant déjà pris par d'autres clés", "refus"],
  "illisible": ["Demande illisible ou mal signée", "refus"],
};

function ligne(dl, terme, valeur, classe) {
  dl.append(element("dt", null, terme));
  const dd = element("dd", classe);
  if (valeur instanceof Node) dd.append(valeur); else dd.textContent = valeur;
  dl.append(dd);
}

function carte(d) {
  const [libelle, genre] = STATUTS[d.statut] || [d.statut, "refus"];
  const c = element("article", "demande");
  const entete = element("div", "entete");
  entete.append(element("h3", null, d.nom || d.nomPropose || "Box " + d.boxId), element("span", "badge " + genre, libelle));
  c.append(entete);

  const dl = element("dl");
  if (d.nom && d.nomPropose && d.nom !== d.nomPropose) ligne(dl, "Nom proposé par la box", d.nomPropose);
  ligne(dl, "Box", d.boxId, "mono");
  if (d.empreinte) ligne(dl, "Empreinte", element("span", "empreinte", d.empreinte));
  if (d.groupes) ligne(dl, "Groupes", d.groupes.length ? d.groupes.join(", ") : "aucun");
  if (d.recueLe) ligne(dl, "Reçue le", new Date(d.recueLe).toLocaleString("fr-FR"));
  if (genre !== "ok") ligne(dl, "Détail", d.detail);
  c.append(dl);

  const choix = {};
  const valider = bouton("Valider", null, () => validerDemande(d, choix));
  valider.disabled = !(d.statut === "code-verifie" || d.statut === "sans-code");

  if (d.statut === "sans-code") {
    const v = element("div", "verification");
    const nom = champ("Nom de la box dans l'annuaire", d.nomPropose, { maxLength: 256 });
    const groupes = champ("Groupes (séparés par des virgules, facultatif)", "", { placeholder: "imagerie-nord, urgences" });
    const verifiee = element("input");
    verifiee.type = "checkbox";
    const lcase = element("label", "choix");
    lcase.append(verifiee, document.createTextNode("J'ai comparé cette empreinte avec l'installateur de la box : elle est identique"));
    v.append(lcase, nom.label, groupes.label);
    c.append(v);
    valider.disabled = true;
    verifiee.addEventListener("change", () => { valider.disabled = !verifiee.checked; });
    Object.assign(choix, { nom: nom.input, groupes: groupes.input, verifiee });
  }

  const actions = element("div", "actions");
  actions.append(valider, bouton("Refuser", "refuser", () => refuserDemande(d)));
  c.append(actions);
  return c;
}

function validerDemande(d, choix) {
  const nom = choix.nom ? choix.nom.value.trim() || d.nomPropose : d.nom;
  const groupes = choix.groupes ? listeGroupes(choix.groupes.value) : d.groupes;
  signer({
    titre: "Valider la demande",
    resume: "Signer l'entrée de la box « " + nom + " » (empreinte " + d.empreinte + ", groupes "
      + (groupes.length ? groupes.join(", ") : "aucun") + ", " + etat.validiteJours
      + " jours) avec la clé de l'organisation, puis la publier dans l'annuaire.",
    libelle: "Signer et publier",
    action: (phrase, garder) => {
      const corps = { boxId: d.boxId, empreinte: d.empreinte, phrase, garder };
      if (choix.verifiee) Object.assign(corps, { empreinteVerifiee: choix.verifiee.checked, nom, groupes });
      return api("POST", "/api/valider", corps);
    },
  });
}

function refuserDemande(d) {
  confirmer({
    titre: "Refuser la demande",
    resume: "Refuser la demande de « " + (d.nom || d.nomPropose || d.boxId) + " » (box " + d.boxId
      + (d.empreinte ? ", empreinte " + d.empreinte : "") + ") ?",
    detail: "La demande disparaît de la liste ; les nouveaux envois de cette box sont ignorés pendant 30 jours.",
    libelle: "Refuser",
    action: () => api("POST", "/api/refuser", { boxId: d.boxId }),
  });
}

function afficherDemandes(demandes) {
  const zone = $("demandes");
  zone.replaceChildren();
  if (!demandes.length) zone.append(element("p", "discret", "Aucune demande en attente."));
  demandes.forEach((d) => zone.append(carte(d)));
  $("compte-demandes").textContent = demandes.length ? "(" + demandes.length + ")" : "";
}

// --- Box de l'organisation ---

const STATUTS_BOX = { ACTIVE: "active", EXPIRED: "expirée", REVOKED: "révoquée", NOT_YET_VALID: "pas encore valide" };

function majSelection() {
  const choisies = document.querySelectorAll("#box input[type=checkbox]:checked").length;
  $("renouveler").disabled = choisies === 0;
  $("selection").textContent = choisies ? choisies + " box choisie(s), renouvelée(s) pour " + etat.validiteJours + " jours" : "";
}

function afficherBox(boites) {
  const corps = $("box");
  corps.replaceChildren();
  if (!boites.length) {
    const td = element("td", "discret", "Aucune box dans l'annuaire.");
    td.colSpan = 7;
    const vide = element("tr");
    vide.append(td);
    corps.append(vide);
  }
  for (const b of boites) {
    const tr = element("tr");
    const choix = element("td");
    if (b.statut !== "REVOKED") {
      const c = element("input");
      c.type = "checkbox";
      c.value = b.boxId;
      c.checked = b.aRenouveler;
      c.dataset.nom = b.nom;
      c.setAttribute("aria-label", "Renouveler " + b.nom);
      c.addEventListener("change", majSelection);
      choix.append(c);
    }
    const nom = element("td");
    nom.append(element("strong", null, b.nom), element("span", "discret mono", b.boxId),
      element("span", "discret", "empreinte " + b.empreinte));
    const statut = element("td", "statut-" + b.statut, STATUTS_BOX[b.statut] || b.statut);
    if (b.statut === "REVOKED") statut.append(element("span", "discret", "le " + jour(b.revoqueeLe) + " : " + b.motif));
    const fin = element("td", b.aRenouveler && b.statut === "ACTIVE" ? "bientot" : null, jour(b.valideJusquau));
    if (b.aRenouveler && b.statut === "ACTIVE") fin.append(element("span", "discret", "expire bientôt"));
    const action = element("td");
    if (b.statut !== "REVOKED") action.append(bouton("Révoquer", "refuser petit", () => revoquer(b)));
    tr.append(choix, nom, statut, element("td", null, b.groupes.length ? b.groupes.join(", ") : "—"), fin,
      element("td", null, String(b.serie)), action);
    corps.append(tr);
  }
  const actives = boites.filter((b) => b.statut === "ACTIVE").length;
  $("compte-box").textContent = boites.length ? "(" + actives + " active" + (actives > 1 ? "s" : "") + ")" : "";
  majSelection();
}

function renouveler() {
  const choisies = [...document.querySelectorAll("#box input[type=checkbox]:checked")];
  signer({
    titre: "Renouveler les entrées",
    resume: "Signer de nouvelles entrées pour " + choisies.map((c) => "« " + c.dataset.nom + " »").join(", ")
      + " : mêmes clés, adresses et groupes, valables " + etat.validiteJours + " jours à partir d'aujourd'hui.",
    libelle: "Signer et publier",
    action: (phrase, garder) => api("POST", "/api/renouveler", { boxIds: choisies.map((c) => c.value), phrase, garder }),
  });
}

function revoquer(b) {
  const bloc = element("div", "verification");
  const motif = champ("Motif (publié dans l'annuaire)", "", { maxLength: 500, placeholder: "box remplacée, clé compromise…" });
  const nom = champ("Pour confirmer, recopiez le nom de la box : " + b.nom, "", { maxLength: 256 });
  bloc.append(motif.label, nom.label);
  signer({
    titre: "Révoquer la box",
    resume: "Révoquer « " + b.nom + " » (" + b.boxId + ") : les autres box cesseront d'échanger avec elle à leur "
      + "prochaine lecture de l'annuaire. Définitif : pour la réintégrer, il faudra l'enrôler de nouveau avec "
      + "d'autres clés.",
    libelle: "Révoquer",
    danger: true,
    extra: bloc,
    action: (phrase, garder) => api("POST", "/api/revoquer", {
      boxId: b.boxId, motif: motif.input.value, confirmation: nom.input.value, phrase, garder,
    }),
  });
}

// --- Codes d'enrôlement ---

const STATUTS_CODE = { libre: ["libre", "statut-ACTIVE"], utilise: ["utilisé", ""], expire: ["expiré", "statut-EXPIRED"] };

function afficherCodes(codes) {
  const corps = $("codes");
  corps.replaceChildren();
  if (!codes.length) {
    const td = element("td", "discret", "Aucun code tiré.");
    td.colSpan = 5;
    const vide = element("tr");
    vide.append(td);
    corps.append(vide);
  }
  for (const c of codes) {
    const [libelle, classe] = STATUTS_CODE[c.statut];
    const statut = element("td", classe, libelle);
    statut.append(element("span", "discret", c.statut === "utilise"
      ? "le " + jour(c.utiliseLe) + ", box " + c.boxId : (c.statut === "libre" ? "jusqu'au " : "le ") + jour(c.expireLe)));
    const action = element("td");
    if (c.statut !== "utilise") action.append(bouton("Retirer", "secondaire petit", () => retirerCode(c)));
    const tr = element("tr");
    tr.append(element("td", null, c.nom), element("td", null, c.groupes.length ? c.groupes.join(", ") : "—"), statut,
      element("td", "mono", c.code || ""), action);
    corps.append(tr);
  }
  const libres = codes.filter((c) => c.statut === "libre").length;
  $("compte-codes").textContent = libres ? "(" + libres + " libre" + (libres > 1 ? "s" : "") + ")" : "";
}

async function tirerCode(evenement) {
  evenement.preventDefault();
  try {
    const r = await api("POST", "/api/codes", {
      nom: $("code-nom").value, groupes: listeGroupes($("code-groupes").value), validiteJours: Number($("code-jours").value),
    });
    $("formulaire-code").reset();
    await charger();
    $("nouveau-nom").textContent = r.nom;
    $("nouveau-expire").textContent = jour(r.expireLe);
    $("nouveau-valeur").textContent = r.code;
    $("nouveau-ligne").textContent = r.ligne;
    $("nouveau-code").hidden = false;
    message(r.message, "ok");
  } catch (e) {
    message(e.message, "erreur");
  }
}

function retirerCode(c) {
  confirmer({
    titre: "Retirer le code",
    resume: "Retirer le code de la box prévue « " + c.nom + " » ?",
    detail: "Une box qui le présenterait ne serait plus reconnue : sa demande attendrait comme une demande sans code.",
    libelle: "Retirer",
    action: () => api("POST", "/api/codes/retirer", { id: c.id }),
  });
}

// --- Organisations partenaires ---

let clePartenaire = null;

function portee(groupes) {
  if (!groupes.length) return "aucun";
  return groupes.includes("*") ? "tous" : groupes.join(", ");
}

function afficherPartenaires(partenaires) {
  const corps = $("partenaires");
  corps.replaceChildren();
  if (!partenaires.length) {
    const td = element("td", "discret", "Aucune organisation reconnue.");
    td.colSpan = 5;
    const vide = element("tr");
    vide.append(td);
    corps.append(vide);
  }
  for (const p of partenaires) {
    const tr = element("tr");
    const nom = element("td");
    nom.append(element("strong", null, p.organisation), element("span", "discret mono", p.empreinteCourte),
      element("span", "discret", p.annuaire));
    const accord = element("td", "statut-" + p.statut, STATUTS_BOX[p.statut] || p.statut);
    accord.append(element("span", "discret", p.statut === "REVOKED" ? "le " + jour(p.revoqueeLe) + " : " + p.motif
      : "jusqu'au " + jour(p.valideJusquau) + ", n° " + p.serie));
    const perimetre = element("td");
    perimetre.append(element("span", null, "locaux : " + portee(p.groupesLocaux)),
      element("span", "discret", "partenaire : " + portee(p.groupesPartenaire)));
    const action = element("td");
    action.append(bouton("Renouveler", "secondaire petit", () => renouvelerAccord(p)));
    if (p.statut !== "REVOKED") action.append(bouton("Révoquer", "refuser petit", () => revoquerAccord(p)));
    tr.append(nom, accord, perimetre, element("td", "small", p.relais.join(", ")), action);
    corps.append(tr);
  }
  const actifs = partenaires.filter((p) => p.statut === "ACTIVE").length;
  $("compte-partenaires").textContent = partenaires.length ? "(" + actifs + " actif" + (actifs > 1 ? "s" : "") + ")" : "";
}

async function lireCle() {
  clePartenaire = null;
  $("partenaire-verifiee").checked = false;
  $("partenaire-verifiee").disabled = true;
  $("partenaire-signer").disabled = true;
  try {
    clePartenaire = await api("POST", "/api/partenaires/cle", { cle: $("partenaire-cle").value });
    $("partenaire-empreinte").textContent = "« " + clePartenaire.organisation + " », empreinte "
      + clePartenaire.empreinteCourte;
    $("partenaire-empreinte").className = "empreinte";
    $("partenaire-verifiee").disabled = false;
  } catch (e) {
    $("partenaire-empreinte").textContent = "";
    message(e.message, "erreur");
  }
}

function reconnaitre(evenement) {
  evenement.preventDefault();
  const corps = {
    cle: $("partenaire-cle").value, empreinte: clePartenaire.empreinte, empreinteVerifiee: $("partenaire-verifiee").checked,
    annuaire: $("partenaire-annuaire").value, relais: listeGroupes($("partenaire-relais").value),
    groupesLocaux: listeGroupes($("partenaire-locaux").value), groupesPartenaire: listeGroupes($("partenaire-distants").value),
  };
  signer({
    titre: "Reconnaître une organisation",
    resume: "Signer la reconnaissance de « " + clePartenaire.organisation + " » (empreinte " + clePartenaire.empreinteCourte
      + ", groupes locaux " + portee(corps.groupesLocaux) + ", groupes du partenaire " + portee(corps.groupesPartenaire)
      + ", " + etat.validiteJours + " jours) avec la clé de l'organisation, puis la publier.",
    libelle: "Signer et publier",
    action: async (phrase, garder) => {
      const r = await api("POST", "/api/partenaires", Object.assign(corps, { phrase, garder }));
      $("formulaire-partenaire").reset();
      $("partenaire-empreinte").textContent = "";
      $("partenaire-signer").disabled = true;
      $("partenaire-verifiee").disabled = true;
      clePartenaire = null;
      return r;
    },
  });
}

function renouvelerAccord(p) {
  signer({
    titre: "Renouveler l'accord",
    resume: "Signer une nouvelle reconnaissance de « " + p.organisation + " » (même clé, mêmes relais, même périmètre), "
      + "valable " + etat.validiteJours + " jours à partir d'aujourd'hui" + (p.statut === "REVOKED" ? " : l'accord révoqué est rétabli." : "."),
    libelle: "Signer et publier",
    action: (phrase, garder) => api("POST", "/api/partenaires/renouveler", { empreinte: p.empreinte, phrase, garder }),
  });
}

function revoquerAccord(p) {
  const bloc = element("div", "verification");
  const motif = champ("Motif (publié dans l'annuaire)", "", { maxLength: 500, placeholder: "fin du partenariat…" });
  const nom = champ("Pour confirmer, recopiez le nom de l'organisation : " + p.organisation, "", { maxLength: 64 });
  bloc.append(motif.label, nom.label);
  signer({
    titre: "Révoquer l'accord",
    resume: "Révoquer la reconnaissance de « " + p.organisation + " » : les box des deux organisations cesseront "
      + "d'échanger à leur prochaine lecture de l'annuaire.",
    libelle: "Révoquer",
    danger: true,
    extra: bloc,
    action: (phrase, garder) => api("POST", "/api/partenaires/revoquer", {
      empreinte: p.empreinte, motif: motif.input.value, confirmation: nom.input.value, phrase, garder,
    }),
  });
}

// --- Chargement ---

// --- Démarrage ---

function statut(id, fait, texteFait, texteAFaire, genre) {
  const b = $("statut-" + id);
  b.textContent = fait ? texteFait : texteAFaire;
  b.className = "badge " + (fait ? "ok" : genre || "attention");
  const etape = b.closest(".etape");
  if (etape) etape.classList.toggle("faite", Boolean(fait));
  return fait ? 1 : 0;
}

function copiable(id, texte) {
  const conteneur = $(id);
  const code = element("code", null, texte);
  conteneur.replaceChildren(code, bouton("Copier", "secondaire petit", () => copier(texte)));
}

async function copier(texte) {
  try {
    await navigator.clipboard.writeText(texte);
    message("Copié dans le presse-papiers", "ok");
  } catch (e) {
    message("Copie impossible : sélectionner le texte à la main", "erreur");
  }
}

function afficherDemarrage(d, extras) {
  const e = d.etat;
  const a = d.annuaire || {};
  let faites = 0;
  $("org-dossier").textContent = e.dossier;
  $("organisation-a-creer").hidden = e.initialisee;
  $("organisation-creee").hidden = !e.initialisee;
  faites += statut("organisation", e.initialisee, "faite", "à faire");
  const empreinte = e.initialisee ? e.empreinteOrganisation : "XXXX-XXXX-XXXX-XXXX-XXXX-XXXX";
  if (e.initialisee) {
    $("org-nom").textContent = e.organisation;
    $("org-empreinte").textContent = e.empreinteOrganisation;
    $("org-cle-privee").textContent = e.fichierCle;
    $("org-cle-publique").textContent = e.fichierCle + ".pub.json";
  }
  copiable("ligne-env-empreinte", "EMPREINTE_RESPONSABLE=" + empreinte);
  copiable("ligne-bat-empreinte", 'set "ORGANISATION_CODE=' + empreinte + '"');
  if (!a.joignable) {
    faites += statut("site", false, "", "annuaire injoignable", "refus");
    $("site-detail").textContent = "Annuaire " + e.annuaire + " injoignable" + (a.detail ? " (" + a.detail + ")" : "")
      + " : normal tant que le site central n'est pas installé.";
  } else if (!e.initialisee || !a.memeCle) {
    faites += statut("site", false, "", e.initialisee ? "clé différente" : "en attente de l'étape 1", e.initialisee ? "refus" : "attention");
    $("site-detail").textContent = "L'annuaire publie l'organisation « " + a.organisation + " », empreinte " + a.empreinte
      + (e.initialisee ? " : ce n'est pas la clé de ce poste. Installer la clé publique de l'étape 1, puis le redémarrer." : ".");
  } else {
    faites += statut("site", true, "annuaire installé avec votre clé", "");
    $("site-detail").textContent = "L'annuaire " + e.annuaire + " publie bien « " + a.organisation + " » avec l'empreinte " + a.empreinte + ".";
  }
  $("formulaire-compte").hidden = e.compte !== "requis";
  faites += statut("compte", e.compte !== "requis", e.compte === "saisi" ? "connecté pour cette session" : "compte fourni au lancement", "à saisir");
  const f = d.fraicheur || {};
  $("fraicheur-fichiers").textContent = f.cle + " et " + f.delegation;
  $("deleguer").disabled = !e.initialisee;
  $("deleguer").textContent = f.numero ? "Renouveler la délégation pour 30 jours" : "Déléguer la clé de fraîcheur pour 30 jours";
  $("fraicheur-detail").textContent = f.erreur || (f.numero ? "Délégation n° " + f.numero + " jusqu'au " + jour(f.jusquAu)
    + (a.fraicheurPubliee ? " ; l'annuaire publie sa preuve" : " ; pas encore installée sur l'annuaire") : "Pas encore déléguée.");
  faites += statut("fraicheur", f.numero && !f.expireBientot && a.fraicheurPubliee, "publiée",
    f.expireBientot ? "à renouveler" : f.numero ? "à installer" : "à faire");
  $("lignes-box").textContent = ["IMAGUNITY_DIRECTORY_ENABLED=true",
    "IMAGUNITY_DIRECTORY_URL=" + e.annuaire,
    "IMAGUNITY_DIRECTORY_ORGANIZATION_KEY=/etc/imagunity/organisation.pub.json",
    "IMAGUNITY_DIRECTORY_ORGANIZATION_CODE=" + empreinte,
    "IMAGUNITY_DIRECTORY_ENROLLMENT_CODE=<code tiré pour cette box>"].join("\n");
  const boites = extras ? extras.boites.length : 0;
  faites += statut("box", boites > 0, boites + " box enrôlée(s)", extras ? (extras.codes.length ? extras.codes.length + " code(s) tiré(s)" : "aucun code tiré") : "après l'étape 1");
  const demandes = extras ? extras.demandes.length : 0;
  statut("demandes", extras && demandes === 0 && boites > 0, "rien en attente", demandes ? demandes + " en attente" : "aucune demande");
  $("compte-demarrage").textContent = faites + "/5";
}

async function creerOrganisation(evenement) {
  evenement.preventDefault();
  $("org-creer").disabled = true;
  try {
    const r = await api("POST", "/api/organisation", { organisation: $("org-id").value.trim(),
      phrase: $("org-phrase").value, confirmation: $("org-confirmation").value });
    $("org-phrase").value = "";
    $("org-confirmation").value = "";
    message(r.message + " Empreinte : " + r.empreinte, "ok");
    await charger();
  } catch (e) {
    message(e.message, "erreur");
  } finally {
    $("org-creer").disabled = false;
  }
}

async function seConnecter(evenement) {
  evenement.preventDefault();
  try {
    const r = await api("POST", "/api/compte", { utilisateur: $("compte-utilisateur").value, motDePasse: $("compte-mot-de-passe").value });
    $("compte-mot-de-passe").value = "";
    message(r.message, "ok");
    await charger();
  } catch (e) {
    message(e.message, "erreur");
  }
}

function deleguer() {
  signer({
    titre: "Déléguer la clé de fraîcheur",
    resume: "Une nouvelle clé de fraîcheur est créée sur ce poste et autorisée pour 30 jours par la clé de l'organisation. "
      + "Ses deux fichiers sont ensuite à installer sur le service d'annuaire (étape 4).",
    libelle: "Signer la délégation",
    action: (phrase, garder) => api("POST", "/api/fraicheur", { phrase, garder, jours: 30 }),
  });
}

async function telechargerCle() {
  try {
    const r = await api("GET", "/api/cle-publique");
    const lien = element("a");
    lien.href = URL.createObjectURL(new Blob([r.contenu], { type: "application/json" }));
    lien.download = "organisation.pub.json";
    document.body.append(lien);
    lien.click();
    lien.remove();
    setTimeout(() => URL.revokeObjectURL(lien.href), 10000);
  } catch (e) {
    message(e.message, "erreur");
  }
}


// --- Configuration des box : nœuds et routes DICOM ---

let configs = null;
let edition = null;
let editionModifiee = false;

const texteFiltre = (m) => Object.entries(m || {}).map(([k, v]) => k + "=" + v).join("; ");

function lireFiltre(texte, quoi) {
  const m = {};
  for (const morceau of texte.split(";").map((x) => x.trim()).filter(Boolean)) {
    const i = morceau.indexOf("=");
    if (i < 1) throw new Error(quoi + " : « " + morceau + " » n'a pas la forme Champ=motif");
    m[morceau.slice(0, i).trim()] = morceau.slice(i + 1).trim();
  }
  return m;
}

function copieDicom(d) {
  return {
    aet: (d && d.aet) || "",
    nodes: ((d && d.nodes) || []).map((n) => ({ name: n.name, aet: n.aet, host: n.host || "", port: n.port || "",
      source: !!n.source, destination: !!n.destination })),
    outgoing: ((d && d.outgoing) || []).map((r) => ({ name: r.name, from: [...(r.from || [])], calledAet: r.calledAet || "",
      and: texteFiltre(r.and), or: texteFiltre(r.or), to: [...(r.to || [])] })),
    incoming: ((d && d.incoming) || []).map((r) => ({ name: r.name, from: [...(r.from || [])],
      and: texteFiltre(r.and), or: texteFiltre(r.or), to: [...(r.to || [])] })),
  };
}

function boxChoisie() {
  return configs && configs.box.find((b) => b.boxId === $("config-box").value);
}

function modifie() {
  editionModifiee = true;
}

function afficherConfigurations(c) {
  configs = c;
  const select = $("config-box");
  const avant = select.value;
  select.replaceChildren(...c.box.map((b) => {
    const o = element("option", null, b.nom);
    o.value = b.boxId;
    return o;
  }));
  if (c.box.some((b) => b.boxId === avant)) select.value = avant;
  $("config-copier").replaceChildren(element("option", null, "—"), ...c.box.map((b) => {
    const o = element("option", null, b.nom);
    o.value = b.boxId;
    return o;
  }));
  $("config-copier").firstChild.value = "";
  $("config-editeur").hidden = c.box.length === 0;
  if (c.box.length === 0) {
    $("config-etat").textContent = "Aucune box active dans l'annuaire.";
    return;
  }
  afficherEtatBox();
  if (!editionModifiee) chargerEdition();
}

function afficherEtatBox() {
  const b = boxChoisie();
  if (!b) return;
  const morceaux = [b.numero ? "Version en vigueur : n° " + b.numero + " (" + b.cible + ")" : "Aucune configuration centrale : configuration locale"];
  if (b.appliquee !== undefined && b.appliquee !== null) morceaux.push("appliquée par la box : n° " + b.appliquee);
  if (b.refusee) morceaux.push("refus de la n° " + b.refusee + " : " + b.refus);
  if (b.groupes && b.groupes.length) morceaux.push("groupes : " + b.groupes.join(", "));
  $("config-etat").textContent = morceaux.join(" — ");
  $("config-regles").textContent = (b.priorite || b.routage)
    ? "Les règles " + [b.priorite ? "de priorité" : null, b.routage ? "de routage par champ" : null].filter(Boolean).join(" et ")
      + " du document en vigueur sont reprises telles quelles dans le nouveau document."
    : "";
}

function chargerEdition(depuis) {
  const b = boxChoisie();
  if (!b) return;
  const source = depuis ? configs.box.find((x) => x.boxId === depuis) : b;
  edition = copieDicom(source && source.dicom);
  editionModifiee = !!depuis;
  $("config-aet").value = edition.aet;
  rendreNoeuds();
  rendreRoutes();
}

function caseACocher(libelle, coche, action) {
  const input = element("input");
  input.type = "checkbox";
  input.checked = coche;
  input.addEventListener("change", () => { action(input.checked); modifie(); });
  const label = element("label");
  label.append(input, document.createTextNode(libelle));
  return label;
}

function champTexte(valeur, action, options) {
  const input = element("input");
  input.type = (options && options.type) || "text";
  input.value = valeur === null || valeur === undefined ? "" : valeur;
  if (options && options.placeholder) input.placeholder = options.placeholder;
  if (options && options.maxLength) input.maxLength = options.maxLength;
  input.addEventListener("input", () => { action(input.value); modifie(); });
  if (options && options.surChangement) input.addEventListener("change", options.surChangement);
  return input;
}

function rendreNoeuds() {
  const corps = $("config-noeuds");
  corps.replaceChildren();
  if (!edition.nodes.length) {
    const td = element("td", "discret", "Aucun nœud : la configuration locale de la box s'applique tant qu'aucun n'est déclaré.");
    td.colSpan = 7;
    const tr = element("tr");
    tr.append(td);
    corps.append(tr);
  }
  edition.nodes.forEach((n, i) => {
    const tr = element("tr");
    const cellule = (contenu) => { const td = element("td"); td.append(contenu); tr.append(td); };
    cellule(champTexte(n.name, (v) => { n.name = v; }, { placeholder: "pacs-nord", maxLength: 64, surChangement: rendreRoutes }));
    cellule(champTexte(n.aet, (v) => { n.aet = v; }, { placeholder: "PACS_NORD", maxLength: 16 }));
    cellule(champTexte(n.host, (v) => { n.host = v; }, { placeholder: "10.2.0.15", maxLength: 253 }));
    cellule(champTexte(n.port, (v) => { n.port = v; }, { type: "number", placeholder: "104" }));
    cellule(caseACocher("", n.source, (v) => { n.source = v; rendreRoutes(); }));
    cellule(caseACocher("", n.destination, (v) => { n.destination = v; rendreRoutes(); }));
    cellule(bouton("Retirer", "secondaire petit", () => { edition.nodes.splice(i, 1); modifie(); rendreNoeuds(); rendreRoutes(); }));
    corps.append(tr);
  });
}

function nomBox(id) {
  const t = configs.destinations.find((d) => d.boxId === id);
  return t ? t.nom + (t.organisation !== etat.organisation ? " (" + t.organisation + ")" : "") : id;
}

function listeChoix(titre, options, choisis, vide) {
  const bloc = element("div");
  bloc.append(element("div", "discret", titre));
  const liste = element("div", "choix-liste");
  if (!options.length) liste.append(element("span", "discret", vide));
  options.forEach(([valeur, libelle]) => liste.append(caseACocher(libelle, choisis.includes(valeur), (c) => {
    const i = choisis.indexOf(valeur);
    if (c && i < 0) choisis.push(valeur);
    if (!c && i >= 0) choisis.splice(i, 1);
  })));
  bloc.append(liste);
  return bloc;
}

function rendreRoute(r, i, sortante) {
  const liste = sortante ? edition.outgoing : edition.incoming;
  const carte = element("div", "route");
  const ligne = element("div", "route-ligne");
  const libelle = (t, champ) => { const l = element("label", null, t); l.append(champ); return l; };
  ligne.append(libelle("Nom de la route", champTexte(r.name, (v) => { r.name = v; }, { maxLength: 64 })));
  if (sortante) {
    ligne.append(libelle("AET appelé exigé (facultatif)", champTexte(r.calledAet, (v) => { r.calledAet = v; },
      { maxLength: 16, placeholder: "toute destination de la box" })));
  }
  ligne.append(libelle("Filtre : tous ces champs (Champ=motif; …)", champTexte(r.and, (v) => { r.and = v; },
    { placeholder: "Modality=CT; SeriesDescription=*THORAX*" })));
  ligne.append(libelle("Filtre : un de ces champs", champTexte(r.or, (v) => { r.or = v; }, { placeholder: "facultatif" })));
  carte.append(ligne);
  const autre = $("config-box").value;
  const boxes = configs.destinations.filter((d) => d.boxId !== autre).map((d) => [d.boxId, nomBox(d.boxId)]);
  if (sortante) {
    const sources = edition.nodes.filter((n) => n.source && n.name).map((n) => [n.name, n.name + " (" + n.aet + ")"]);
    carte.append(listeChoix("Sources (aucune cochée : toutes)", sources, r.from, "Aucun nœud source déclaré"));
    carte.append(listeChoix("Box destinataires", boxes, r.to, "Aucune autre box active"));
  } else {
    carte.append(listeChoix("Box émettrices (aucune cochée : toutes)", boxes, r.from, "Aucune autre box active"));
    const destinations = edition.nodes.filter((n) => n.destination && n.name).map((n) => [n.name, n.name + " (" + n.aet + ")"]);
    carte.append(listeChoix("PACS destinataires", destinations, r.to, "Aucun nœud destination déclaré"));
  }
  const actions = element("div", "actions");
  actions.append(bouton("Monter", "secondaire petit", () => {
    if (i > 0) { liste.splice(i - 1, 0, liste.splice(i, 1)[0]); modifie(); rendreRoutes(); }
  }), bouton("Retirer la route", "secondaire petit", () => { liste.splice(i, 1); modifie(); rendreRoutes(); }));
  carte.append(actions);
  return carte;
}

function rendreRoutes() {
  $("config-sortantes").replaceChildren(...(edition.outgoing.length ? edition.outgoing.map((r, i) => rendreRoute(r, i, true))
    : [element("p", "discret", "Aucune route sortante.")]));
  $("config-entrantes").replaceChildren(...(edition.incoming.length ? edition.incoming.map((r, i) => rendreRoute(r, i, false))
    : [element("p", "discret", "Aucune route entrante : restitution au PACS de la configuration locale.")]));
}

function dicomAPublier() {
  const vide = (t) => (t || "").trim() === "" ? null : t.trim();
  return {
    aet: vide($("config-aet").value),
    nodes: edition.nodes.map((n) => ({ name: n.name.trim(), aet: n.aet.trim(), host: vide(n.host),
      port: n.port === "" || n.port === null ? null : Number(n.port), source: n.source, destination: n.destination })),
    outgoing: edition.outgoing.map((r) => ({ name: r.name.trim(), from: r.from, calledAet: vide(r.calledAet),
      and: lireFiltre(r.and, "Route " + r.name), or: lireFiltre(r.or, "Route " + r.name), to: r.to })),
    incoming: edition.incoming.map((r) => ({ name: r.name.trim(), from: r.from,
      and: lireFiltre(r.and, "Route " + r.name), or: lireFiltre(r.or, "Route " + r.name), to: r.to })),
  };
}

function publierConfiguration() {
  const b = boxChoisie();
  let dicom;
  try {
    dicom = dicomAPublier();
  } catch (e) {
    message(e.message, "erreur");
    return;
  }
  const resume = dicom.nodes.length + " nœud(s), " + dicom.outgoing.length + " route(s) sortante(s), "
    + dicom.incoming.length + " route(s) entrante(s)";
  signer({
    titre: "Signer la configuration de « " + b.nom + " »",
    resume: "Document n° " + configs.prochainNumero + " pour cette box seule : " + resume
      + ". Il remplace sa configuration DICOM locale dès sa prochaine lecture de l'annuaire.",
    libelle: "Signer et publier",
    action: async (phrase, garder) => {
      const r = await api("POST", "/api/configuration", { boxId: b.boxId, dicom, motif: $("config-motif").value, phrase, garder });
      editionModifiee = false;
      $("config-motif").value = "";
      return r;
    },
  });
}

/** Onglet dont la liste n'a pas pu être lue : le motif s'affiche à sa place. */
function indisponible(onglet, texte) {
  const zone = $(onglet);
  if (zone.tagName === "TBODY") {
    const td = element("td", "erreur-texte", texte);
    td.colSpan = 8;
    const tr = element("tr");
    tr.append(td);
    zone.replaceChildren(tr);
  } else {
    zone.replaceChildren(element("p", "erreur-texte", texte));
  }
  const compteur = $("compte-" + onglet);
  if (compteur) compteur.textContent = "";
}

async function charger() {
  try {
    etat = await api("GET", "/api/etat");
    afficherEtat();
    if (!etat.initialisee) {
      afficherDemarrage(await api("GET", "/api/demarrage"), null);
      ongletActif(["demarrage", "aide"].includes(ongletCourant()) ? ongletCourant() : "demarrage");
      return;
    }
    // Démarrage d'abord : il s'affiche même si l'annuaire est injoignable ou le compte pas encore saisi.
    const demarrage = await api("GET", "/api/demarrage");
    afficherDemarrage(demarrage, null);
    // Chaque onglet se charge seul : un annuaire injoignable ou un compte pas encore saisi n'empêche pas les autres.
    const resultats = await Promise.allSettled([
      etat.compte === "requis" ? Promise.reject(new Error("Compte de dépôt de l'annuaire à saisir : onglet « Démarrage », étape 3"))
        : api("GET", "/api/demandes"),
      api("GET", "/api/box"), api("GET", "/api/codes"), api("GET", "/api/partenaires")]);
    const [demandes, boites, codes, partenaires] = resultats.map((r, i) => {
      if (r.status === "fulfilled") return r.value;
      indisponible(["demandes", "box", "codes", "partenaires"][i], r.reason.message);
      return null;
    });
    try {
      afficherConfigurations(await api("GET", "/api/configurations"));
    } catch (e) {
      $("config-etat").textContent = "Configurations illisibles : " + e.message;
    }
    if (demandes) afficherDemandes(demandes);
    if (boites) afficherBox(boites);
    if (codes) afficherCodes(codes);
    if (partenaires) afficherPartenaires(partenaires);
    afficherDemarrage(demarrage, { demandes: demandes || [], boites: boites || [], codes: codes || [] });
  } catch (e) {
    message(e.message, "erreur");
  }
}

document.addEventListener("DOMContentLoaded", () => {
  document.querySelectorAll("[data-onglet]").forEach((b) => b.addEventListener("click", () => ongletActif(b.dataset.onglet)));
  let onglet = "demarrage";
  try { onglet = sessionStorage.getItem("onglet") || onglet; } catch (e) { /* onglet par défaut */ }
  ongletActif(onglet);
  $("formulaire-organisation").addEventListener("submit", creerOrganisation);
  $("formulaire-compte").addEventListener("submit", seConnecter);
  $("deleguer").addEventListener("click", deleguer);
  $("telecharger-cle").addEventListener("click", telechargerCle);
  $("config-box").addEventListener("change", () => { editionModifiee = false; afficherEtatBox(); chargerEdition(); });
  $("config-aet").addEventListener("input", modifie);
  $("ajouter-noeud").addEventListener("click", () => {
    edition.nodes.push({ name: "", aet: "", host: "", port: 104, source: true, destination: false });
    modifie(); rendreNoeuds(); rendreRoutes();
  });
  $("ajouter-sortante").addEventListener("click", () => {
    edition.outgoing.push({ name: "route-" + (edition.outgoing.length + 1), from: [], calledAet: "", and: "", or: "", to: [] });
    modifie(); rendreRoutes();
  });
  $("ajouter-entrante").addEventListener("click", () => {
    edition.incoming.push({ name: "entree-" + (edition.incoming.length + 1), from: [], and: "", or: "", to: [] });
    modifie(); rendreRoutes();
  });
  $("config-publier").addEventListener("click", publierConfiguration);
  $("config-annuler").addEventListener("click", () => { editionModifiee = false; chargerEdition(); message("Modifications annulées", "ok"); });
  $("config-copier").addEventListener("change", () => {
    if (!$("config-copier").value) return;
    chargerEdition($("config-copier").value);
    $("config-copier").value = "";
    message("Configuration reprise : vérifiez les adresses, propres à chaque site, avant de signer.", "ok");
  });
  document.querySelectorAll("[data-copier]").forEach((b) => b.addEventListener("click", () => copier($(b.dataset.copier).textContent)));
  document.querySelectorAll("[data-aller]").forEach((b) => b.addEventListener("click", () => ongletActif(b.dataset.aller)));
  $("formulaire-signer").addEventListener("submit", soumettreSignature);
  $("annuler-signer").addEventListener("click", () => { $("phrase").value = ""; $("dialogue-signer").close(); });
  $("confirmer-oui").addEventListener("click", soumettreConfirmation);
  $("confirmer-non").addEventListener("click", () => $("dialogue-confirmer").close());
  $("renouveler").addEventListener("click", renouveler);
  $("formulaire-code").addEventListener("submit", tirerCode);
  $("partenaire-lire").addEventListener("click", lireCle);
  $("partenaire-cle").addEventListener("input", () => {
    clePartenaire = null;
    $("partenaire-empreinte").textContent = "";
    $("partenaire-verifiee").checked = false;
    $("partenaire-verifiee").disabled = true;
    $("partenaire-signer").disabled = true;
  });
  $("partenaire-verifiee").addEventListener("change", () => {
    $("partenaire-signer").disabled = !($("partenaire-verifiee").checked && clePartenaire);
  });
  $("formulaire-partenaire").addEventListener("submit", reconnaitre);
  $("copier").addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText($("nouveau-ligne").textContent);
      message("Ligne copiée dans le presse-papiers", "ok");
    } catch (e) {
      message("Copie impossible : sélectionner la ligne à la main", "erreur");
    }
  });
  $("actualiser").addEventListener("click", () => { message(""); charger(); });
  $("oublier").addEventListener("click", async () => {
    try {
      etat = await api("POST", "/api/oublier", {});
      afficherEtat();
      message("Clé oubliée : la phrase de passe sera redemandée", "ok");
    } catch (e) {
      message(e.message, "erreur");
    }
  });
  $("quitter").addEventListener("click", async () => {
    try {
      const r = await api("POST", "/api/quitter", {});
      document.querySelectorAll("button").forEach((b) => { b.disabled = true; });
      message(r.message, "ok");
    } catch (e) {
      message(e.message, "erreur");
    }
  });
  if (!jeton) {
    message("Adresse incomplète : ouvrir l'adresse affichée par l'outil du responsable (elle contient le jeton de la session).", "erreur");
    return;
  }
  charger();
  // Actualisation régulière, sauf pendant une saisie (dialogue ouvert, vérification ou formulaire commencés).
  setInterval(() => {
    const actif = document.activeElement;
    const saisie = document.querySelector("dialog[open], .verification input:checked")
      || (actif && actif.closest(".verification, #formulaire-code, #box, #formulaire-partenaire, #formulaire-organisation, #formulaire-compte, #config-editeur"));
    if (!saisie) charger();
  }, 30000);
});
