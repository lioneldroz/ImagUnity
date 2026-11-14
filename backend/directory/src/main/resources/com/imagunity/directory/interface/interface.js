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

function ongletActif(nom) {
  document.querySelectorAll("[data-onglet]").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.onglet === nom)));
  ["demandes", "box", "codes"].forEach((o) => { $("onglet-" + o).hidden = o !== nom; });
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

// --- Chargement ---

async function charger() {
  try {
    etat = await api("GET", "/api/etat");
    afficherEtat();
    const [demandes, boites, codes] = await Promise.all([api("GET", "/api/demandes"), api("GET", "/api/box"), api("GET", "/api/codes")]);
    afficherDemandes(demandes);
    afficherBox(boites);
    afficherCodes(codes);
  } catch (e) {
    message(e.message, "erreur");
  }
}

document.addEventListener("DOMContentLoaded", () => {
  document.querySelectorAll("[data-onglet]").forEach((b) => b.addEventListener("click", () => ongletActif(b.dataset.onglet)));
  let onglet = "demandes";
  try { onglet = sessionStorage.getItem("onglet") || onglet; } catch (e) { /* onglet par défaut */ }
  ongletActif(onglet);
  $("formulaire-signer").addEventListener("submit", soumettreSignature);
  $("annuler-signer").addEventListener("click", () => { $("phrase").value = ""; $("dialogue-signer").close(); });
  $("confirmer-oui").addEventListener("click", soumettreConfirmation);
  $("confirmer-non").addEventListener("click", () => $("dialogue-confirmer").close());
  $("renouveler").addEventListener("click", renouveler);
  $("formulaire-code").addEventListener("submit", tirerCode);
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
      || (actif && actif.closest(".verification, #formulaire-code, #box"));
    if (!saisie) charger();
  }, 30000);
});
