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

// Régions de statut (RGAA 7.5) : toujours présentes, vidées puis remplies pour qu'un même message répété soit annoncé.
const minuteries = new Map();
function annoncer(region, texte) {
  clearTimeout(minuteries.get(region));
  region.textContent = "";
  if (texte) minuteries.set(region, setTimeout(() => { region.textContent = texte; }, 50));
}

function message(texte, genre) {
  const m = $("message");
  m.className = "message " + (genre || "");
  annoncer(m, texte);
}

function element(nom, classe, texte) {
  const e = document.createElement(nom);
  if (classe) e.className = classe;
  if (texte !== undefined && texte !== null) e.textContent = texte;
  return e;
}

function bouton(texte, classe, action, cle) {
  const b = element("button", classe, texte);
  b.type = "button";
  b.addEventListener("click", action);
  if (cle) b.dataset.cle = cle;
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

// --- Focus conservé quand les listes sont redessinées (actualisation automatique, RGAA 7.3) ---

/** Ce qui permet de retrouver l'élément qui a le focus après un nouveau rendu : sa clé, son id, ou sa section. */
function reperFocus() {
  const e = document.activeElement;
  if (!e || e === document.body) return null;
  const section = e.closest("main > section");
  return { cle: e.dataset.cle || null, id: e.id || null, section: section ? section.id : null };
}

/** Rend le focus perdu pendant le rendu : au même élément s'il existe encore, sinon au titre de la section. */
function restaurerFocus(repere) {
  if (!repere || (document.activeElement && document.activeElement !== document.body)) return;
  const cible = (repere.cle && document.querySelector('[data-cle="' + CSS.escape(repere.cle) + '"]'))
    || (repere.id && $(repere.id))
    || (repere.section && $(repere.section).querySelector("h2"));
  if (cible) cible.focus({ preventScroll: true });
}

// --- En-tête et onglets ---

function afficherEtat() {
  document.querySelectorAll("[data-onglet]").forEach((b) => {
    b.disabled = !etat.initialisee && !["demarrage", "aide"].includes(b.dataset.onglet);
  });
  afficherSauvegarde();
  if (!etat.initialisee) {
    $("bandeau-organisation").textContent = "ImagUnity";
    $("bandeau-empreinte").hidden = true;
    $("contexte").textContent = "Aucune organisation sur ce poste pour l'instant : commencez par « Démarrage » — annuaire " + etat.annuaire;
    $("etat-phrase").textContent = "";
    $("oublier").hidden = true;
    return;
  }
  $("bandeau-organisation").textContent = etat.organisation;
  $("bandeau-empreinte").textContent = etat.empreinteOrganisation;
  $("bandeau-empreinte").title = "Empreinte de la clé de l'organisation";
  $("bandeau-empreinte").hidden = false;
  $("contexte").textContent = "Annuaire " + etat.annuaire + " — entrées signées pour " + etat.validiteJours + " jours";
  const textes = {
    inutile: "Clé non protégée (déconseillé)",
    environnement: "Phrase de passe fournie",
    gardee: "Clé déverrouillée",
    requise: "Clé verrouillée",
  };
  $("etat-phrase").textContent = textes[etat.phrase] || "";
  $("oublier").hidden = etat.phrase !== "gardee";
}

// --- Sauvegarde du dossier de l'organisation ---

/** Jours au-delà desquels une sauvegarde est rappelée, même sans fichier modifié. */
const SAUVEGARDE_JOURS = 90;

/** Ce qu'il faut rappeler de la sauvegarde : null si elle est à jour, sinon [urgence, titre, détail]. */
function rappelSauvegarde() {
  const s = etat && etat.initialisee && etat.sauvegarde;
  if (!s) return null;
  if (!s.le) return ["bientot", "Dossier de l'organisation jamais sauvegardé",
    "Sans copie de la clé sur un support séparé, une panne de ce poste obligerait à réenrôler toutes les box."];
  if (s.modifies && s.modifies.length) return ["bientot", "Sauvegarde à refaire : " + s.modifies.join(", ") + " modifié depuis",
    "Dernière copie " + ilYa(s.le) + "."];
  if (Date.now() - new Date(s.le).getTime() > SAUVEGARDE_JOURS * 86400000) return ["", "Dernière sauvegarde " + ilYa(s.le),
    "Vérifiez que la copie est toujours lisible, ou refaites-en une."];
  return null;
}

function afficherSauvegarde() {
  const zone = $("sauvegarde");
  zone.hidden = !(etat && etat.initialisee && etat.sauvegarde);
  if (zone.hidden) return;
  const s = etat.sauvegarde;
  zone.classList.toggle("attention", rappelSauvegarde() !== null);
  $("sauvegarde-texte").textContent = !s.le ? "Aucune copie du dossier de l'organisation pour l'instant."
    : "Dernière copie " + ilYa(s.le) + (s.modifies.length ? " ; modifié depuis : " + s.modifies.join(", ") + "." : ".");
}

/** Archive des fichiers de l'organisation, enregistrée par le navigateur ; à copier sur un support séparé. */
async function sauvegarder() {
  try {
    const r = await api("POST", "/api/sauvegarde", {});
    const octets = Uint8Array.from(atob(r.contenu), (c) => c.charCodeAt(0));
    const lien = element("a");
    lien.href = URL.createObjectURL(new Blob([octets], { type: "application/zip" }));
    lien.download = r.nom;
    document.body.append(lien);
    lien.click();
    lien.remove();
    etat.sauvegarde = r.sauvegarde;
    afficherSauvegarde();
    if (dernierTableau) afficherTableau(dernierTableau);
    message("Archive « " + r.nom + " » remise au navigateur : copiez-la sur un support séparé (clé USB rangée au coffre), "
      + "la phrase de passe ailleurs.", "ok");
  } catch (e) {
    message(e.message, "erreur");
  }
}

function ongletCourant() {
  const b = document.querySelector("[data-onglet][aria-current=page]");
  return b ? b.dataset.onglet : "demarrage";
}

function ongletActif(nom) {
  document.querySelectorAll("[data-onglet]").forEach((b) => {
    if (b.dataset.onglet === nom) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
  });
  ["tableau", "demarrage", "demandes", "box", "codes", "configuration", "partenaires", "aide"].forEach((o) => { $("onglet-" + o).hidden = o !== nom; });
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
  $("confirmer-signer").disabled = false;
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
  const valider = bouton("Valider", null, () => validerDemande(d, choix), "demande:" + d.boxId + ":valider");
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
  actions.append(valider, bouton("Refuser", "refuser", () => refuserDemande(d), "demande:" + d.boxId + ":refuser"));
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
      c.dataset.cle = "box:" + b.boxId + ":choix";
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
    if (b.statut !== "REVOKED") action.append(bouton("Révoquer", "refuser petit", () => revoquer(b), "box:" + b.boxId + ":revoquer"));
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
    if (c.statut !== "utilise") action.append(bouton("Retirer", "secondaire petit", () => retirerCode(c), "code:" + c.id + ":retirer"));
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
    action.append(bouton("Renouveler", "secondaire petit", () => renouvelerAccord(p), "partenaire:" + p.empreinte + ":renouveler"));
    if (p.statut !== "REVOKED") action.append(bouton("Révoquer", "refuser petit", () => revoquerAccord(p), "partenaire:" + p.empreinte + ":revoquer"));
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
  conteneur.replaceChildren(code, bouton("Copier", "secondaire petit", () => copier(texte), "copier:" + id));
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
  dernierDemarrage = d;
  const pastille = $("pastille-annuaire");
  pastille.hidden = false;
  pastille.textContent = a.joignable ? "Annuaire joignable" : "Annuaire injoignable";
  pastille.className = "pastille" + (a.joignable ? "" : " refus");
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
let editionOrigine = null;
let editionModifiee = false;
let boxSelection = null;
let dernierDemarrage = null;

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
  return configs && configs.box.find((b) => b.boxId === boxSelection);
}

function modifie() {
  editionModifiee = true;
  afficherChangements();
}

function etiquetteConfig(b) {
  if (b.refusee && (!b.appliquee || b.refusee > b.appliquee)) return ["refus", "refus"];
  if (!b.numero) return ["locale", "neutre"];
  if (b.appliquee !== undefined && b.appliquee !== null && b.appliquee < b.numero) return ["n° " + b.numero + " en attente", "attention"];
  return ["n° " + b.numero, ""];
}

function choisirBox(id) {
  if (editionModifiee && id !== boxSelection) {
    message("Modifications de « " + (boxChoisie() || {}).nom + " » abandonnées.", "");
  }
  boxSelection = id;
  editionModifiee = false;
  rendreListeBox();
  afficherEtatBox();
  chargerEdition();
}

function rendreListeBox() {
  $("config-liste").replaceChildren(...configs.box.map((b) => {
    const bt = element("button");
    bt.type = "button";
    bt.setAttribute("aria-pressed", String(b.boxId === boxSelection));
    bt.dataset.cle = "config:" + b.boxId;
    const textes = element("span");
    textes.append(element("span", "nom", b.nom), element("span", "groupes", b.groupes && b.groupes.length ? b.groupes.join(", ") : "sans groupe"));
    const [libelle, genre] = etiquetteConfig(b);
    bt.append(textes, element("span", "etiquette " + genre, libelle));
    bt.addEventListener("click", () => choisirBox(b.boxId));
    return bt;
  }));
}

function afficherConfigurations(c) {
  configs = c;
  if (!c.box.some((b) => b.boxId === boxSelection)) {
    boxSelection = c.box.length ? c.box[0].boxId : null;
    editionModifiee = false;
  }
  rendreListeBox();
  const refus = c.box.filter((b) => etiquetteConfig(b)[1] === "refus").length;
  $("compte-configuration").textContent = refus ? String(refus) : "";
  $("config-copier").replaceChildren(element("option", null, "—"), ...c.box.map((b) => {
    const o = element("option", null, b.nom);
    o.value = b.boxId;
    return o;
  }));
  $("config-copier").firstChild.value = "";
  $("config-editeur").hidden = c.box.length === 0;
  $("config-vide").hidden = c.box.length !== 0;
  if (c.box.length === 0) {
    $("config-vide").textContent = "Aucune box active dans l'annuaire : enrôlez-en une d'abord.";
    return;
  }
  afficherEtatBox();
  if (!editionModifiee) chargerEdition();
}

function afficherEtatBox() {
  const b = boxChoisie();
  if (!b) return;
  $("config-titre").textContent = b.nom;
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
  editionOrigine = copieDicom(b.dicom);
  editionModifiee = !!depuis;
  $("config-aet").value = edition.aet;
  rendreNoeuds();
  rendreRoutes();
  afficherChangements();
}

/** Différences entre la configuration publiée et celle en cours d'édition : [signe, genre, texte]. */
function differences(avant, apres) {
  const liste = [];
  if ((avant.aet || "") !== (apres.aet || "")) liste.push(["~", "modif", "AET de la box : " + (avant.aet || "local") + " → " + (apres.aet || "local")]);
  const comparer = (a, b, quoi, decrire) => {
    const parNom = (l) => new Map(l.map((x) => [x.name, x]));
    const ma = parNom(a);
    const mb = parNom(b);
    for (const x of b) {
      if (!ma.has(x.name)) liste.push(["+", "ajout", quoi + " « " + x.name + " » " + decrire(x)]);
      else if (JSON.stringify(ma.get(x.name)) !== JSON.stringify(x)) liste.push(["~", "modif", quoi + " « " + x.name + " » : " + ecarts(ma.get(x.name), x)]);
    }
    for (const x of a) if (!mb.has(x.name)) liste.push(["−", "retrait", quoi + " « " + x.name + " » retiré(e)"]);
    const ordreA = a.map((x) => x.name).filter((n) => mb.has(n));
    const ordreB = b.map((x) => x.name).filter((n) => ma.has(n));
    if (ordreA.join("|") !== ordreB.join("|")) liste.push(["↕", "modif", "Ordre des " + quoi.toLowerCase() + "s changé"]);
  };
  const libelles = { aet: "AET", host: "adresse", port: "port", source: "source", destination: "destination",
    from: "de", calledAet: "AET appelé", and: "filtre (tous)", or: "filtre (un de)", to: "vers" };
  const valeur = (k, v) => {
    if (v === null || v === undefined || v === "" || (Array.isArray(v) && !v.length)) return k === "from" ? "toutes" : "—";
    if (typeof v === "boolean") return v ? "oui" : "non";
    if (Array.isArray(v)) return v.map((x) => (typeof x === "object" ? JSON.stringify(x) : (k === "to" || k === "from") && nomBox(x) || x)).join(", ");
    if (typeof v === "object") return Object.entries(v).map(([c, m]) => c + "=" + m).join("; ");
    return String(v);
  };
  const ecarts = (x, y) => Object.keys(libelles)
    .filter((k) => JSON.stringify(x[k] === undefined ? null : x[k]) !== JSON.stringify(y[k] === undefined ? null : y[k]))
    .map((k) => (libelles[k] || k) + " " + valeur(k, x[k]) + " → " + valeur(k, y[k])).join(" · ") || "modifié";
  const role = (n) => [n.source ? "source" : null, n.destination ? "destination" : null].filter(Boolean).join(" et ") || "sans rôle";
  comparer(avant.nodes, apres.nodes, "Appareil", (n) => "· " + n.aet + (n.host ? " · " + n.host + (n.port ? ":" + n.port : "") : "") + " · " + role(n));
  comparer(avant.outgoing, apres.outgoing, "Envoi", (r) => "→ " + (r.to.length ? r.to.map(nomBox).join(", ") : "aucune box"));
  comparer(avant.incoming, apres.incoming, "Réception", (r) => "→ " + (r.to.length ? r.to.join(", ") : "aucun PACS"));
  return liste;
}

function afficherChangements() {
  if (!edition || !editionOrigine) return;
  const edite = Object.assign({}, edition, { aet: $("config-aet").value.trim() });
  const n = differences(editionOrigine, edite).length;
  const zone = $("config-changements");
  zone.replaceChildren();
  if (!n) {
    zone.textContent = "Aucun changement à publier";
  } else {
    zone.append(element("strong", null, n + " changement" + (n > 1 ? "s" : "")), document.createTextNode(" non publié" + (n > 1 ? "s" : "")));
  }
  $("config-publier").disabled = n === 0;
}

function caseACocher(libelle, coche, action, nom) {
  const input = element("input");
  input.type = "checkbox";
  input.checked = coche;
  if (nom) input.setAttribute("aria-label", nom);
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
  if (options && options.nom) input.setAttribute("aria-label", options.nom);
  input.addEventListener("input", () => { action(input.value); modifie(); });
  if (options && options.surChangement) input.addEventListener("change", options.surChangement);
  return input;
}

/** Temps écoulé depuis une date, en clair : « à l'instant », « il y a 3 min », « il y a 2 h », « il y a 4 j ». */
function ilYa(iso) {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return "à l'instant";
  if (s < 3600) return "il y a " + Math.floor(s / 60) + " min";
  if (s < 86400) return "il y a " + Math.floor(s / 3600) + " h";
  return "il y a " + Math.floor(s / 86400) + " j";
}

const memeAppareil = (a, b) => a && b && a.aet === b.aet && (a.host || "") === (b.host || "")
  && String(a.port || "") === String(b.port || "") && !!a.source === !!b.source && !!a.destination === !!b.destination;

/**
 * État d'un appareil tel que la box le constate (dernier compte rendu) : une liste de [texte, genre, détail], une par
 * constat. Un appareil ajouté ou modifié ici n'a pas encore été essayé : la box l'essaiera à l'application.
 */
function etatAppareil(n, b) {
  const publie = editionOrigine && editionOrigine.nodes.find((x) => x.name === n.name);
  if (!publie) return [["Nouveau : pas encore essayé", "neutre", "La box essaiera la connexion dès qu'elle appliquera la configuration."]];
  if (!memeAppareil(publie, n)) return [["Modifié : sera essayé", "neutre", "La box essaiera la connexion dès qu'elle appliquera la configuration."]];
  const c = b && b.appareils && b.appareils.noeuds && b.appareils.noeuds[n.name];
  const morceaux = [];
  if (n.destination) {
    if (!c || !c.echoAt) morceaux.push(["Pas encore essayé", "neutre", "La box n'a pas encore rendu compte d'un essai de connexion."]);
    else if (c.echoError) morceaux.push(["C-ECHO en échec " + ilYa(c.echoAt), "refus", c.echoError]);
    else morceaux.push(["C-ECHO réussi " + ilYa(c.echoAt), "", "Le " + new Date(c.echoAt).toLocaleString("fr-FR")]);
  }
  if (n.source) {
    if (c && c.refusedAt && (!c.receivedAt || c.refusedAt > c.receivedAt)) {
      morceaux.push(["Connexion refusée " + ilYa(c.refusedAt), "attention", "Venue de " + c.refusedFrom
        + (n.host ? ", alors que l'adresse déclarée est " + n.host : "") + "."]);
    } else if (c && c.receivedAt) {
      morceaux.push(["Envoi reçu " + ilYa(c.receivedAt), "", "Le " + new Date(c.receivedAt).toLocaleString("fr-FR")]);
    } else if (!n.destination) {
      morceaux.push(["Aucun envoi reçu", "neutre", "La box n'a encore reçu aucune association de cet appareil."]);
    }
  }
  return morceaux.length ? morceaux : [["—", "neutre", "Ni source ni destination."]];
}

/** La destination n'a pas encore été essayée par la box (nouvelle, modifiée ou sans compte rendu). */
const pasEncoreEssaye = (n, b) => n.destination && etatAppareil(n, b).some((m) => m[1] === "neutre");

/** Destinations qui ne répondent pas à l'essai de connexion, d'après le dernier compte rendu de la box. */
function appareilsEnEchec(b) {
  const noeuds = (b && b.appareils && b.appareils.noeuds) || {};
  const declares = (b && b.dicom && b.dicom.nodes) || [];
  return declares.filter((n) => n.destination && noeuds[n.name] && noeuds[n.name].echoError)
    .map((n) => [n.name, noeuds[n.name].echoError]);
}

function rendreNoeuds() {
  const corps = $("config-noeuds");
  corps.replaceChildren();
  if (!edition.nodes.length) {
    const td = element("td", "discret", "Aucun nœud : la configuration locale de la box s'applique tant qu'aucun n'est déclaré.");
    td.colSpan = 8;
    const tr = element("tr");
    tr.append(td);
    corps.append(tr);
  }
  const b = boxChoisie();
  edition.nodes.forEach((n, i) => {
    const tr = element("tr");
    const cellule = (contenu) => { const td = element("td"); td.append(contenu); tr.append(td); };
    // Champs sans étiquette visible (en-têtes de colonne) : nommés par la colonne et le rang de l'appareil (RGAA 11.1).
    const rang = " de l'appareil " + (i + 1);
    cellule(champTexte(n.name, (v) => { n.name = v; }, { placeholder: "pacs-nord", maxLength: 64, surChangement: rendreRoutes, nom: "Nom" + rang }));
    cellule(champTexte(n.aet, (v) => { n.aet = v; }, { placeholder: "PACS_NORD", maxLength: 16, nom: "AET" + rang }));
    cellule(champTexte(n.host, (v) => { n.host = v; }, { placeholder: "10.2.0.15", maxLength: 253, nom: "Adresse" + rang }));
    cellule(champTexte(n.port, (v) => { n.port = v; }, { type: "number", placeholder: "104", nom: "Port" + rang }));
    cellule(caseACocher("", n.source, (v) => { n.source = v; rendreRoutes(); }, "Source" + rang));
    cellule(caseACocher("", n.destination, (v) => { n.destination = v; rendreRoutes(); }, "Destination" + rang));
    const td = element("td", "etat-appareils");
    for (const [texte, genre, detail] of etatAppareil(n, b)) {
      const etiquette = element("span", "etiquette etat-appareil " + genre, texte);
      etiquette.title = detail;
      td.append(etiquette);
      if (genre === "refus" || genre === "attention") td.append(element("span", "discret detail-appareil", detail));
    }
    tr.append(td);
    cellule(bouton("Retirer", "secondaire petit", () => { edition.nodes.splice(i, 1); modifie(); rendreNoeuds(); rendreRoutes(); }));
    corps.append(tr);
  });
}

function nomBox(id) {
  const t = configs.destinations.find((d) => d.boxId === id);
  return t ? t.nom + (t.organisation !== etat.organisation ? " (" + t.organisation + ")" : "") : id;
}

function listeChoix(titre, options, choisis, vide) {
  // Cases liées regroupées sous leur titre (RGAA 11.5).
  const bloc = element("fieldset", "choix-groupe");
  bloc.append(element("legend", "discret", titre));
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

function resumeFlux(r, i, sortante) {
  const flux = element("div", "flux");
  const morceau = (classe, texte) => flux.append(element("span", classe, texte));
  const fleche = () => morceau("fleche", "→");
  morceau("rang", String(i + 1));
  morceau("titre-route", r.name || "(sans nom)");
  const filtre = [r.and, r.or ? "ou " + r.or : ""].filter((x) => x && x.trim()).join(" ; ") || "aucun filtre";
  if (sortante) {
    morceau("de", r.from.length ? r.from.join(", ") : "tout appareil source");
    fleche();
    morceau("filtre", (r.calledAet ? "AET " + r.calledAet + " ; " : "") + filtre);
    fleche();
    morceau("vers", r.to.length ? r.to.map(nomBox).join(", ") : "aucune box");
  } else {
    morceau("vers", r.from.length ? r.from.map(nomBox).join(", ") : "toute box");
    fleche();
    morceau("filtre", filtre);
    fleche();
    morceau("de", r.to.length ? r.to.join(", ") : "aucun PACS");
  }
  return flux;
}

function rendreRoute(r, i, sortante) {
  const liste = sortante ? edition.outgoing : edition.incoming;
  const carte = element("div", "route");
  let resume = resumeFlux(r, i, sortante);
  carte.append(resume);
  const majResume = () => { const nouveau = resumeFlux(r, i, sortante); resume.replaceWith(nouveau); resume = nouveau; };
  carte.addEventListener("input", majResume);
  carte.addEventListener("change", majResume);
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
  const autre = boxSelection;
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

// --- Essai sur un examen fictif ---

/** Origines proposées : chaque appareil source (envoi), chaque autre box (réception). */
function rendreEssai() {
  const choix = $("essai-de");
  const avant = choix.value;
  choix.replaceChildren();
  const groupe = (libelle) => { const g = element("optgroup"); g.label = libelle; choix.append(g); return g; };
  const option = (g, valeur, texte) => { const o = element("option", null, texte); o.value = valeur; g.append(o); };
  const sources = edition.nodes.filter((n) => n.source && n.name);
  if (sources.length) {
    const g = groupe("Envoi vers la box, depuis un appareil");
    sources.forEach((n) => option(g, "envoi:" + n.name, n.name + (n.aet ? " (" + n.aet + ")" : "")));
  }
  const g = groupe("Réception, depuis une box");
  configs.destinations.filter((d) => d.boxId !== boxSelection)
    .forEach((d) => option(g, "reception:" + d.boxId, nomBox(d.boxId)));
  option(g, "reception:", "une autre box");
  if ([...choix.options].some((o) => o.value === avant)) choix.value = avant;
  majEssai();
}

function majEssai() {
  const envoi = $("essai-de").value.startsWith("envoi:");
  $("essai-aet-label").hidden = !envoi;
}

async function essayer() {
  const zone = $("essai-resultat");
  zone.className = "essai-resultat";
  let dicom;
  try {
    dicom = dicomAPublier();
  } catch (e) {
    zone.classList.add("refus");
    annoncer(zone, e.message);
    return;
  }
  const [sens, de] = $("essai-de").value.split(/:(.*)/s);
  try {
    const r = await api("POST", "/api/configuration/essai", { dicom, sens, de, aetAppele: $("essai-aet").value.trim(), champs: $("essai-champs").value });
    let texte;
    if (sens === "envoi") {
      texte = r.route
        ? "Envoi « " + r.route + " » (n° " + r.rang + ") → " + (r.vers.length ? r.vers.map(nomBox).join(", ") : "aucune box") + "."
        : "Aucun envoi ne correspond : la box refuserait l'instance, et l'appareil la garderait en échec.";
    } else {
      texte = r.route
        ? "Réception « " + r.route + " » (n° " + r.rang + ") → " + r.vers.join(", ") + "."
        : dicom.incoming.length
          ? "Aucune réception ne correspond : la box ne restituerait pas l'instance (signalé dans sa console)."
          : "Aucune réception déclarée : la box restituerait au PACS de sa configuration locale.";
    }
    if (!r.route) zone.classList.add("refus");
    annoncer(zone, texte);
  } catch (e) {
    zone.classList.add("refus");
    annoncer(zone, e.message);
  }
}

function rendreRoutes() {
  if (configs) rendreEssai();
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
  const extra = element("div", "signature-config");
  const liste = element("ul", "differences");
  differences(editionOrigine, Object.assign({}, edition, { aet: $("config-aet").value.trim() })).forEach(([signe, genre, texte]) => {
    const li = element("li");
    li.append(element("span", "signe " + genre, signe), element("span", null, texte));
    liste.append(li);
  });
  const relu = element("input");
  relu.type = "checkbox";
  const lrelu = element("label", "choix");
  lrelu.append(relu, document.createTextNode("J'ai relu ces changements : c'est bien ce que je veux signer"));
  extra.append(element("p", "discret", "Ce qui change par rapport à la version en vigueur :"), liste);
  const aEssayer = edition.nodes.filter((n) => pasEncoreEssaye(n, b)).map((n) => n.name);
  const enEchec = appareilsEnEchec(b).filter(([nom]) => edition.nodes.some((n) => n.name === nom
    && memeAppareil(n, editionOrigine.nodes.find((x) => x.name === nom))));
  if (aEssayer.length) {
    extra.append(element("p", "remarque", (aEssayer.length > 1 ? aEssayer.join(", ") + " n'ont" : aEssayer[0] + " n'a")
      + " pas encore répondu à un essai de connexion. La box l'essaiera dès qu'elle appliquera ce document ; le résultat s'affichera dans la colonne État."));
  }
  if (enEchec.length) {
    extra.append(element("p", "remarque refus", "Ne répond toujours pas : " + enEchec.map(([nom, e]) => nom + " (" + e + ")").join(" ; ")
      + ". Vérifiez son adresse, son port, son AET, et que la box y est autorisée."));
  }
  extra.append(lrelu);
  signer({
    titre: "Signer la configuration de « " + b.nom + " »",
    resume: "Document n° " + configs.prochainNumero + " pour cette box seule (" + resume
      + "). La box l'applique à sa prochaine lecture de l'annuaire, sans redémarrage.",
    libelle: "Signer et publier",
    extra,
    action: async (phrase, garder) => {
      const r = await api("POST", "/api/configuration", { boxId: b.boxId, dicom, motif: $("config-motif").value, phrase, garder });
      editionModifiee = false;
      $("config-motif").value = "";
      return r;
    },
  });
  $("confirmer-signer").disabled = true;
  relu.addEventListener("change", () => { $("confirmer-signer").disabled = !relu.checked; });
}

// --- Tableau de bord ---

let tableauBox = [];

let dernierTableau = null;

function afficherTableau(donnees) {
  dernierTableau = donnees;
  const { demandes, boites, partenaires, demarrage } = donnees;
  const afaire = [];
  const ajouter = (urgence, titre, detail, action, aller) => afaire.push({ urgence, titre, detail, action, aller });
  const a = (demarrage && demarrage.annuaire) || {};
  const f = (demarrage && demarrage.fraicheur) || {};
  if (demarrage && !a.joignable) {
    ajouter("urgent", "Annuaire injoignable", "Adresse " + etat.annuaire + (a.detail ? " (" + a.detail + ")" : ""), "Voir", "demarrage");
  }
  if (etat.compte === "requis") {
    ajouter("bientot", "Compte de dépôt de l'annuaire à saisir", "Nécessaire pour valider les demandes et publier.", "Saisir", "demarrage");
  }
  if (demandes && demandes.length) {
    const codes = demandes.filter((d) => d.statut === "code-verifie").length;
    ajouter("", demandes.length + " demande" + (demandes.length > 1 ? "s" : "") + " d'enrôlement en attente",
      codes === demandes.length ? "Toutes portent un code vérifié : validation en un clic." : codes + " avec un code vérifié.", "Valider", "demandes");
  }
  if (!f.numero) {
    ajouter("bientot", "Clé de fraîcheur pas encore déléguée", "Les box ne peuvent pas vérifier que l'annuaire est à jour.", "Déléguer", "demarrage");
  } else if (f.expireBientot) {
    ajouter("urgent", "Délégation de fraîcheur : expire le " + jour(f.jusquAu), "Sans renouvellement, les échanges s'arrêtent 48 h après la fin.", "Renouveler", "demarrage");
  } else if (!a.fraicheurPubliee && a.joignable) {
    ajouter("bientot", "Délégation n° " + f.numero + " à installer sur l'annuaire", "L'annuaire ne publie pas encore de preuve de fraîcheur.", "Voir", "demarrage");
  }
  const aRenouveler = (boites || []).filter((b) => b.aRenouveler);
  if (aRenouveler.length) {
    ajouter("bientot", aRenouveler.length + " entrée" + (aRenouveler.length > 1 ? "s" : "") + " de box à renouveler",
      aRenouveler.map((b) => b.nom).join(", ") + ".", "Renouveler", "box");
  }
  for (const b of (configs ? configs.box : [])) {
    if (etiquetteConfig(b)[1] === "refus") {
      ajouter("urgent", "« " + b.nom + " » a refusé la configuration n° " + b.refusee, b.refus || "", "Corriger", "configuration:" + b.boxId);
    }
  }
  for (const b of (configs ? configs.box : [])) {
    const echecs = appareilsEnEchec(b);
    if (echecs.length) {
      ajouter("urgent", "« " + b.nom + " » : " + echecs.map(([nom]) => nom).join(", ") + (echecs.length > 1 ? " ne répondent pas" : " ne répond pas"),
        echecs.map(([nom, e]) => nom + " : " + e).join(" ; "), "Voir", "configuration:" + b.boxId);
    }
  }
  const rappel = rappelSauvegarde();
  if (rappel) ajouter(rappel[0], rappel[1], rappel[2], "Sauvegarder", "sauvegarder");
  const ordre = { urgent: 0, bientot: 1, "": 2 };
  afaire.sort((x, y) => ordre[x.urgence] - ordre[y.urgence]);
  const zone = $("afaire");
  zone.replaceChildren();
  if (!afaire.length) zone.append(element("li", "vide", "Rien à faire : tout est à jour."));
  for (const t of afaire) {
    const li = element("li");
    const texte = element("div", "texte");
    texte.append(element("div", "titre", t.titre), element("div", "discret", t.detail));
    // L'urgence est écrite, pas seulement signalée par une couleur (RGAA 3.1).
    const urgence = { urgent: ["Urgent", "refus"], bientot: ["Bientôt", "attention"] }[t.urgence];
    const repere = urgence ? element("span", "etiquette urgence " + urgence[1], urgence[0]) : element("span", "point");
    if (!urgence) repere.setAttribute("aria-hidden", "true");
    li.append(repere, texte, bouton(t.action, "secondaire", () => aller(t.aller), "afaire:" + t.aller + ":" + t.titre));
    zone.append(li);
  }
  $("tableau-resume").textContent = afaire.length
    ? afaire.length + " action" + (afaire.length > 1 ? "s vous attendent" : " vous attend") + "."
    : "Tout est à jour.";
  $("compte-tableau").textContent = afaire.length ? String(afaire.length) : "";

  const actives = (boites || []).filter((b) => b.statut === "ACTIVE").length;
  const configurees = configs ? configs.box.filter((b) => b.numero && b.appliquee === b.numero).length : 0;
  const refus = configs ? configs.box.filter((b) => etiquetteConfig(b)[1] === "refus").length : 0;
  const accords = (partenaires || []).filter((p) => p.statut === "ACTIVE").length;
  const indicateur = (libelle, valeur, note, refusNote) => {
    const d = element("div", "indicateur");
    d.append(element("div", "discret", libelle), element("div", "valeur", valeur), element("div", "note" + (refusNote ? " refus" : ""), note));
    return d;
  };
  $("indicateurs").replaceChildren(
    indicateur("Box actives", boites ? actives + " / " + boites.length : "—", boites ? (boites.length - actives) + " autre(s) : révoquées ou expirées" : "annuaire illisible"),
    indicateur("Preuve de fraîcheur", a.fraicheurPubliee ? "publiée" : "absente", f.numero ? "délégation n° " + f.numero + " jusqu'au " + jour(f.jusquAu) : "clé pas encore déléguée", !a.fraicheurPubliee),
    indicateur("Configuration centrale", configs ? configurees + " / " + configs.box.length + " box" : "—", refus ? refus + " refus à corriger" : "appliquée par les box concernées", refus > 0),
    indicateur("Organisations partenaires", String(accords), accords ? "accords actifs" : "aucun accord actif"));

  const parBox = new Map((configs ? configs.box : []).map((b) => [b.boxId, b]));
  tableauBox = (boites || []).map((b) => ({ b, c: parBox.get(b.boxId) }));
  rendreTableauBox();
}

function aller(cible) {
  if (cible === "sauvegarder") {
    sauvegarder();
    return;
  }
  const [onglet, box] = cible.split(":");
  ongletActif(onglet);
  if (box && configs) choisirBox(box);
}

function rendreTableauBox() {
  const filtre = ($("tableau-recherche").value || "").trim().toLowerCase();
  const corps = $("tableau-box");
  corps.replaceChildren();
  const etats = { ACTIVE: ["Active", ""], REVOKED: ["Révoquée", "neutre"], EXPIRED: ["Expirée", "refus"], NOT_YET_VALID: ["Pas encore valide", "attention"] };
  const lignes = tableauBox.filter(({ b }) => !filtre || b.nom.toLowerCase().includes(filtre)
    || (b.groupes || []).some((g) => g.includes(filtre)));
  if (!lignes.length) {
    const td = element("td", "discret", tableauBox.length ? "Aucune box ne correspond." : "Aucune box dans l'annuaire.");
    td.colSpan = 5;
    const tr = element("tr");
    tr.append(td);
    corps.append(tr);
  }
  for (const { b, c } of lignes) {
    const tr = element("tr");
    const [libelle, genre] = b.aRenouveler && b.statut === "ACTIVE" ? ["À renouveler", "attention"] : (etats[b.statut] || [b.statut, "neutre"]);
    const config = c ? etiquetteConfig(c) : ["—", "neutre"];
    const cellule = (contenu) => { const td = element("td"); td.append(contenu); tr.append(td); };
    cellule(element("strong", null, b.nom));
    cellule(element("span", "etiquette " + genre, libelle));
    cellule(document.createTextNode(b.groupes && b.groupes.length ? b.groupes.join(", ") : "—"));
    cellule(element("span", "etiquette " + config[1], config[0]));
    cellule(document.createTextNode(jour(b.valideJusquau)));
    corps.append(tr);
  }
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
      $("config-vide").hidden = false;
      $("config-vide").textContent = "Configurations illisibles : " + e.message;
    }
    afficherTableau({ demandes, boites, partenaires, demarrage });
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
  let onglet = "tableau";
  try { onglet = sessionStorage.getItem("onglet") || onglet; } catch (e) { /* onglet par défaut */ }
  ongletActif(onglet);
  $("formulaire-organisation").addEventListener("submit", creerOrganisation);
  $("formulaire-compte").addEventListener("submit", seConnecter);
  $("deleguer").addEventListener("click", deleguer);
  $("telecharger-cle").addEventListener("click", telechargerCle);
  $("config-aet").addEventListener("input", modifie);
  $("tableau-recherche").addEventListener("input", rendreTableauBox);
  $("essai-de").addEventListener("change", majEssai);
  $("sauvegarder").addEventListener("click", sauvegarder);
  $("essai-lancer").addEventListener("click", essayer);
  $("essai-champs").addEventListener("keydown", (e) => { if (e.key === "Enter") essayer(); });
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
  // Bulles d'aide : Échap les ferme sans déplacer le pointeur ni le focus (RGAA 10.13).
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") document.querySelectorAll(".info:hover, .info:focus-within").forEach((i) => i.classList.add("ferme"));
  });
  document.querySelectorAll(".info").forEach((i) => {
    i.addEventListener("mouseleave", () => i.classList.remove("ferme"));
    i.addEventListener("focusout", () => i.classList.remove("ferme"));
  });
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
    if (!saisie) {
      const repere = reperFocus();
      charger().then(() => restaurerFocus(repere));
    }
  }, 30000);
});
