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
let enCours = null;

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

const STATUTS = {
  "code-verifie": ["Code d'enrôlement vérifié", "ok"],
  "sans-code": ["Sans code : empreinte à vérifier", "attention"],
  "preuve-invalide": ["Preuve du code invalide", "refus"],
  "code-utilise": ["Code déjà utilisé", "refus"],
  "code-expire": ["Code expiré", "refus"],
  "illisible": ["Demande illisible ou mal signée", "refus"],
};

function afficherEtat() {
  $("contexte").textContent = "Organisation " + etat.organisation + " (empreinte " + etat.empreinteOrganisation
    + ") — annuaire " + etat.annuaire + " — entrées signées pour " + etat.validiteJours + " jours";
  const textes = {
    inutile: "Clé non protégée par phrase de passe (déconseillé)",
    environnement: "Phrase de passe fournie par l'environnement",
    gardee: "Clé déverrouillée pour cette session",
    requise: "Phrase de passe demandée à chaque validation",
  };
  $("etat-phrase").textContent = textes[etat.phrase] || "";
  $("oublier").hidden = etat.phrase !== "gardee";
}

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

  const valider = element("button", null, "Valider");
  valider.type = "button";
  const refuser = element("button", "refuser", "Refuser");
  refuser.type = "button";
  const signable = d.statut === "code-verifie" || d.statut === "sans-code";
  valider.disabled = !signable;
  const choix = { nom: null, groupes: null, verifiee: null };

  if (d.statut === "sans-code") {
    const v = element("div", "verification");
    const nom = element("input");
    nom.type = "text";
    nom.value = d.nomPropose || "";
    nom.maxLength = 256;
    const lnom = element("label", null, "Nom de la box dans l'annuaire");
    lnom.append(nom);
    const groupes = element("input");
    groupes.type = "text";
    groupes.placeholder = "imagerie-nord, urgences";
    const lgroupes = element("label", null, "Groupes (séparés par des virgules, facultatif)");
    lgroupes.append(groupes);
    const case_ = element("input");
    case_.type = "checkbox";
    const lcase = element("label", "choix");
    lcase.append(case_, document.createTextNode("J'ai comparé cette empreinte avec l'installateur de la box : elle est identique"));
    v.append(lcase, lnom, lgroupes);
    c.append(v);
    valider.disabled = true;
    case_.addEventListener("change", () => { valider.disabled = !case_.checked; });
    Object.assign(choix, { nom, groupes, verifiee: case_ });
  }

  valider.addEventListener("click", () => ouvrirValidation(d, choix));
  refuser.addEventListener("click", () => ouvrirRefus(d));
  const actions = element("div", "actions");
  actions.append(valider, refuser);
  c.append(actions);
  return c;
}

async function charger() {
  try {
    etat = await api("GET", "/api/etat");
    afficherEtat();
    const demandes = await api("GET", "/api/demandes");
    const zone = $("demandes");
    zone.replaceChildren();
    if (!demandes.length) {
      zone.append(element("p", "discret", "Aucune demande en attente."));
    }
    demandes.forEach((d) => zone.append(carte(d)));
  } catch (e) {
    message(e.message, "erreur");
  }
}

function ouvrirValidation(d, choix) {
  enCours = { d, choix };
  const nom = choix.nom ? choix.nom.value.trim() || d.nomPropose : d.nom;
  const groupes = choix.groupes ? choix.groupes.value.split(",").map((g) => g.trim()).filter(Boolean) : d.groupes;
  enCours.nom = nom;
  enCours.groupes = groupes;
  $("valider-resume").textContent = "Signer l'entrée de la box « " + nom + " » (empreinte " + d.empreinte
    + ", groupes " + (groupes.length ? groupes.join(", ") : "aucun") + ", " + etat.validiteJours
    + " jours) avec la clé de l'organisation, puis la publier dans l'annuaire.";
  const phrase = etat.phrase === "requise";
  $("bloc-phrase").hidden = !phrase;
  $("bloc-garder").hidden = !phrase;
  $("phrase").value = "";
  $("phrase").required = phrase;
  $("garder").checked = false;
  $("dialogue-valider").showModal();
  if (phrase) $("phrase").focus();
}

async function valider(evenement) {
  evenement.preventDefault();
  const { d, choix } = enCours;
  const corps = { boxId: d.boxId, empreinte: d.empreinte, phrase: $("phrase").value, garder: $("garder").checked };
  if (choix.verifiee) {
    Object.assign(corps, { empreinteVerifiee: choix.verifiee.checked, nom: enCours.nom, groupes: enCours.groupes });
  }
  $("confirmer-valider").disabled = true;
  try {
    const r = await api("POST", "/api/valider", corps);
    $("phrase").value = "";
    $("dialogue-valider").close();
    message(r.message, "ok");
    await charger();
  } catch (e) {
    $("phrase").value = "";
    if (!/phrase de passe/i.test(e.message)) $("dialogue-valider").close();
    message(e.message, "erreur");
  } finally {
    $("confirmer-valider").disabled = false;
  }
}

function ouvrirRefus(d) {
  enCours = { d };
  $("refuser-resume").textContent = "Refuser la demande de « " + (d.nom || d.nomPropose || d.boxId) + " » (box "
    + d.boxId + (d.empreinte ? ", empreinte " + d.empreinte : "") + ") ?";
  $("dialogue-refuser").showModal();
}

async function refuser() {
  try {
    const r = await api("POST", "/api/refuser", { boxId: enCours.d.boxId });
    message(r.message, "ok");
  } catch (e) {
    message(e.message, "erreur");
  }
  $("dialogue-refuser").close();
  await charger();
}

document.addEventListener("DOMContentLoaded", () => {
  $("formulaire-valider").addEventListener("submit", valider);
  $("annuler-valider").addEventListener("click", () => { $("phrase").value = ""; $("dialogue-valider").close(); });
  $("confirmer-refuser").addEventListener("click", refuser);
  $("annuler-refuser").addEventListener("click", () => $("dialogue-refuser").close());
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
  // Actualisation régulière, sauf pendant une saisie (dialogue ouvert, vérification commencée).
  setInterval(() => {
    const saisie = document.querySelector("dialog[open], .verification input:checked")
      || (document.activeElement && document.activeElement.closest(".verification"));
    if (!saisie) charger();
  }, 30000);
});
