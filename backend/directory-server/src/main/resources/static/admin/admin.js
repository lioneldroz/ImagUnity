// Interface d'administration de la configuration centralisée : lecture de l'état, préparation des brouillons, essai
// des règles, publication des documents signés. Ne signe rien : la clé du responsable reste hors ligne.
"use strict";

const ENTETE = { "Content-Type": "application/json", "X-ImagUnity-Admin": "1" };
let etat = null;

const $ = (id) => document.getElementById(id);
const date = (iso) => (iso ? new Date(iso).toLocaleString("fr-FR") : "—");

function message(texte, genre) {
  const m = $("message");
  m.textContent = texte;
  m.className = "message " + (genre || "");
  m.hidden = !texte;
}

async function api(methode, chemin, corps) {
  const reponse = await fetch(chemin, {
    method: methode, credentials: "same-origin",
    headers: methode === "GET" ? {} : ENTETE,
    body: corps === undefined ? undefined : (typeof corps === "string" ? corps : JSON.stringify(corps)),
  });
  const texte = await reponse.text();
  if (!reponse.ok) {
    let detail = texte;
    try { detail = JSON.parse(texte).detail || texte; } catch (e) { /* texte brut */ }
    throw new Error(detail || "HTTP " + reponse.status);
  }
  return texte ? JSON.parse(texte) : null;
}

function cellule(ligne, texte, classe) {
  const td = document.createElement("td");
  td.textContent = texte;
  if (classe) td.className = classe;
  ligne.appendChild(td);
  return td;
}

function afficher() {
  $("organisation").textContent = etat.organization;
  $("prochaine").textContent = "n° " + etat.nextSequence;
  const corps = $("box");
  corps.replaceChildren();
  const choix = $("choix-box");
  const coches = new Set([...choix.querySelectorAll("input:checked")].map((c) => c.value));
  choix.replaceChildren();
  for (const b of etat.boxes) {
    const ligne = document.createElement("tr");
    const nom = cellule(ligne, b.displayName);
    const id = document.createElement("div");
    id.className = "discret";
    id.textContent = b.boxId;
    nom.appendChild(id);
    cellule(ligne, b.groups.length ? b.groups.join(", ") : "—");
    cellule(ligne, { ACTIVE: "active", REVOKED: "révoquée", EXPIRED: "expirée", NOT_YET_VALID: "pas encore valide" }[b.status] || b.status, "statut-" + b.status);
    cellule(ligne, b.applied == null ? "pas de compte rendu" : (b.applied === 0 ? "configuration locale" : "n° " + b.applied + " (" + date(b.appliedAt) + ")"));
    cellule(ligne, b.refusal ? "n° " + b.refused + " : " + b.refusal : "—", b.refusal ? "refus" : "");
    cellule(ligne, date(b.reportedAt));
    corps.appendChild(ligne);

    if (b.status !== "ACTIVE") continue; // seules les box actives peuvent être visées
    const label = document.createElement("label");
    const case_ = document.createElement("input");
    case_.type = "checkbox";
    case_.value = b.boxId;
    case_.checked = coches.has(b.boxId);
    label.append(case_, " " + b.displayName);
    choix.appendChild(label);
  }
  if (!etat.boxes.length) {
    const ligne = document.createElement("tr");
    cellule(ligne, "Aucune box dans l'annuaire").colSpan = 6;
    corps.appendChild(ligne);
  }

  const groupes = $("choix-groupes");
  const groupesCoches = new Set([...groupes.querySelectorAll("input:checked")].map((c) => c.value));
  groupes.replaceChildren();
  for (const g of etat.groups) {
    const label = document.createElement("label");
    const case_ = document.createElement("input");
    case_.type = "checkbox";
    case_.value = g;
    case_.checked = groupesCoches.has(g);
    const membres = etat.boxes.filter((b) => b.status === "ACTIVE" && b.groups.includes(g)).length;
    label.append(case_, " groupe « " + g + " » (" + membres + " box)");
    groupes.appendChild(label);
  }

  const versions = $("versions");
  versions.replaceChildren();
  if (!etat.documents.length) {
    versions.textContent = "Aucune version publiée : les box appliquent leur configuration locale.";
  }
  for (const d of etat.documents) {
    const bloc = document.createElement("div");
    bloc.className = "version";
    const titre = document.createElement("h3");
    titre.textContent = "n° " + d.sequence + " — " + date(d.issuedAt);
    const infos = document.createElement("div");
    infos.className = "discret";
    infos.textContent = [
      d.target.all ? "toute l'organisation" : [
        (d.target.boxes || []).length ? d.target.boxes.length + " box" : null,
        (d.target.groups || []).length ? "groupes " + d.target.groups.join(", ") : null,
      ].filter(Boolean).join(" et "),
      d.author ? "par " + d.author : null, d.comment || null,
      d.priority ? "priorité : " + d.priority.rules.length + " règle(s)" : "priorité locale",
      d.routing ? "routage : " + d.routing.rules.length + " règle(s)" : "routage local",
    ].filter(Boolean).join(" · ");
    const reprendre = document.createElement("button");
    reprendre.type = "button";
    reprendre.className = "secondaire";
    reprendre.textContent = "Reprendre comme brouillon";
    reprendre.addEventListener("click", () => reprendreVersion(d));
    bloc.append(titre, infos, reprendre);
    versions.appendChild(bloc);
  }
}

function reprendreVersion(d) {
  $("priorite").value = d.priority ? JSON.stringify(d.priority, null, 2) : "";
  $("routage").value = d.routing ? JSON.stringify(d.routing, null, 2) : "";
  document.querySelector("input[name=cible][value=" + (d.target.all ? "toutes" : "liste") + "]").checked = true;
  for (const c of $("choix-box").querySelectorAll("input")) c.checked = !d.target.all && (d.target.boxes || []).includes(c.value);
  for (const c of $("choix-groupes").querySelectorAll("input")) c.checked = !d.target.all && (d.target.groups || []).includes(c.value);
  $("motif").value = "";
  $("prepare").hidden = true;
  message("Version n° " + d.sequence + " reprise comme brouillon : la modifier, puis préparer un nouveau document.", "ok");
  $("priorite").scrollIntoView({ behavior: "smooth", block: "center" });
}

function lireJson(id, nom) {
  const texte = $(id).value.trim();
  if (!texte) return null;
  try { return JSON.parse(texte); } catch (e) { throw new Error(nom + " : JSON invalide (" + e.message + ")"); }
}

function brouillon() {
  const toutes = document.querySelector("input[name=cible]:checked").value === "toutes";
  const boxes = [...$("choix-box").querySelectorAll("input:checked")].map((c) => c.value);
  const groups = [...$("choix-groupes").querySelectorAll("input:checked")].map((c) => c.value);
  return {
    target: toutes ? { all: true } : { all: false, boxes, groups },
    author: $("auteur").value, comment: $("motif").value,
    priority: lireJson("priorite", "Priorité"), routing: lireJson("routage", "Routage"),
  };
}

async function charger() {
  try {
    etat = await api("GET", "/admin/api/state");
    afficher();
  } catch (e) {
    message("Lecture de l'état impossible : " + e.message, "erreur");
  }
}

async function preparer() {
  try {
    const document_ = await api("POST", "/admin/api/draft", brouillon());
    const json = JSON.stringify(document_, null, 2) + "\n";
    const lien = $("telecharger");
    if (lien.href) URL.revokeObjectURL(lien.href);
    lien.href = URL.createObjectURL(new Blob([json], { type: "application/json" }));
    const fichier = "brouillon-" + document_.sequence + ".json";
    lien.download = fichier;
    $("numero").textContent = document_.sequence;
    $("commandes").textContent =
      "java -cp … com.imagunity.directory.DirectoryAdmin configuration-signer --cles organisation.json \\\n" +
      "    --brouillon " + fichier + " --sortie configuration-" + document_.sequence + ".json\n" +
      "# puis le publier ci-dessous, ou :\n" +
      "java -cp … com.imagunity.directory.DirectoryAdmin configuration-publier --annuaire " + location.origin + " \\\n" +
      "    --configuration configuration-" + document_.sequence + ".json";
    $("prepare").hidden = false;
    message("Règles vérifiées : document n° " + document_.sequence + " prêt à signer.", "ok");
  } catch (e) {
    $("prepare").hidden = true;
    message("Brouillon refusé : " + e.message, "erreur");
  }
}

async function essayer() {
  try {
    const entete = {};
    for (const ligne of $("entete").value.split("\n")) {
      const t = ligne.trim();
      if (!t) continue;
      const i = t.indexOf("=");
      if (i < 1) throw new Error("ligne « " + t + " » : forme Mot-clé=valeur attendue");
      entete[t.slice(0, i).trim()] = t.slice(i + 1).trim();
    }
    const r = await api("POST", "/admin/api/trial", { draft: brouillon(), header: entete });
    const p = $("resultat");
    const morceaux = [];
    if (r.priority) morceaux.push("Priorité " + r.priority + (r.priorityRule ? " (règle « " + r.priorityRule + " »)" : " (par défaut)"));
    if (r.routingRule) morceaux.push("routage vers " + r.destinations.join(", ") + " (règle « " + r.routingRule + " »)");
    else morceaux.push("aucune règle de routage ne correspond");
    p.textContent = morceaux.join(" ; ") + ".";
    p.hidden = false;
    message("", "");
  } catch (e) {
    message("Essai impossible : " + e.message, "erreur");
  }
}

async function publier() {
  const fichier = $("fichier").files[0];
  if (!fichier) { message("Choisir d'abord le document signé.", "erreur"); return; }
  try {
    await api("POST", "/admin/api/publish", await fichier.text());
    message("Document publié : les box visées l'appliqueront à leur prochaine lecture de l'annuaire.", "ok");
    $("fichier").value = "";
    await charger();
  } catch (e) {
    message("Publication refusée : " + e.message, "erreur");
  }
}

document.addEventListener("DOMContentLoaded", () => {
  $("rafraichir").addEventListener("click", charger);
  $("preparer").addEventListener("click", preparer);
  $("essayer").addEventListener("click", essayer);
  $("publier").addEventListener("click", publier);
  charger();
});
