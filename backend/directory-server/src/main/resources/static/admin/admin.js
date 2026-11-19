// Interface d'administration de la configuration centralisée : lecture de l'état, préparation des brouillons, essai
// des règles, publication des documents signés. Ne signe rien : la clé du responsable reste hors ligne.
"use strict";

const ENTETE = { "Content-Type": "application/json", "X-ImagUnity-Admin": "1" };
let etat = null;

const $ = (id) => document.getElementById(id);

// Région de statut toujours présente : vidée puis remplie, pour qu'un même message répété soit annoncé de nouveau.
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

function element(nom, classe, texte) {
  const e = document.createElement(nom);
  if (classe) e.className = classe;
  if (texte !== undefined && texte !== null) e.textContent = texte;
  return e;
}

/** Pastille d'état : point de couleur et texte ; genre : "" (fait), "neutre", "attention", "refus". */
const pastille = (texte, genre) => element("span", "etat" + (genre ? " " + genre : ""), texte);

function cellule(ligne, contenu, sousLigne) {
  const td = document.createElement("td");
  if (contenu instanceof Node) td.append(contenu); else td.textContent = contenu;
  if (sousLigne) td.append(element("span", "sous-ligne", sousLigne));
  ligne.appendChild(td);
  return td;
}

// --- Onglets ---

const ONGLETS = ["box", "brouillon", "versions"];

function ongletActif(nom, focaliser) {
  if (!ONGLETS.includes(nom)) nom = "box";
  for (const b of document.querySelectorAll("[data-onglet]")) {
    if (b.dataset.onglet === nom) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
  }
  for (const o of ONGLETS) $("onglet-" + o).hidden = o !== nom;
  if (location.hash !== "#" + nom) history.replaceState(null, "", "#" + nom);
  if (focaliser) $("titre-" + nom).focus(); // changement de page : le focus va à son titre (RGAA 12.6)
}

// --- Box et demandes ---

const STATUTS = { ACTIVE: ["active", ""], REVOKED: ["révoquée", "neutre"], EXPIRED: ["expirée", "refus"],
  NOT_YET_VALID: ["pas encore valide", "attention"] };
/** Date en clair : « aujourd'hui, 14:02 », « hier, 18:40 », « 12/11, 09:15 » ; avec l'année pour un document (« 16/11/2026, 09:00 »). */
function courte(iso, annee) {
  if (!iso) return "—";
  const d = new Date(iso);
  const heure = d.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
  const jour = (x) => x.toLocaleDateString("fr-FR");
  if (!annee && jour(d) === jour(new Date())) return "aujourd'hui, " + heure;
  if (!annee && jour(d) === jour(new Date(Date.now() - 86400000))) return "hier, " + heure;
  return d.toLocaleDateString("fr-FR", annee ? undefined : { day: "2-digit", month: "2-digit" }) + ", " + heure;
}
const derniere = () => etat.documents.reduce((m, d) => Math.max(m, d.sequence), 0);

function indicateur(libelle, valeur, note, genre) {
  const li = element("li", "indicateur");
  li.append(element("div", "libelle", libelle), element("div", "valeur", valeur));
  if (note) li.append(element("div", "note" + (genre ? " " + genre : ""), note));
  return li;
}

function afficherIndicateurs(demandes) {
  const b = etat.boxes;
  const actives = b.filter((x) => x.status === "ACTIVE").length;
  const autres = [["REVOKED", "révoquée"], ["EXPIRED", "expirée"], ["NOT_YET_VALID", "pas encore valide"]]
    .map(([s, t]) => [b.filter((x) => x.status === s).length, t]).filter(([n]) => n).map(([n, t]) => n + " " + t + (n > 1 && !t.includes(" ") ? "s" : ""));
  const n = derniere();
  const appliquee = b.filter((x) => n && x.applied === n).length;
  const refus = b.filter((x) => x.refusal);
  const sansCode = demandes ? demandes.filter((d) => !d.withCode).length : 0;
  $("indicateurs").replaceChildren(
    indicateur("Box actives", actives + " / " + b.length, autres.join(", ")),
    indicateur("Configuration en vigueur", n ? "n° " + n : "locale",
      n ? "appliquée par " + appliquee + " box" : "aucune version publiée", n ? "ok" : ""),
    indicateur("Refus", String(refus.length), refus.length ? refus.map((x) => x.displayName + ", n° " + x.refused).join(" ; ") : "aucun",
      refus.length ? "refus" : ""),
    indicateur("Demandes en attente", String(demandes ? demandes.length : 0), sansCode ? sansCode + " sans code" : "",
      sansCode ? "attention" : ""));
}

function filtrerBox() {
  const q = $("recherche").value.trim().toLowerCase();
  for (const tr of $("box").querySelectorAll("tr[data-texte]")) tr.hidden = !!q && !tr.dataset.texte.includes(q);
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
    ligne.dataset.texte = (b.displayName + " " + b.boxId + " " + b.groups.join(" ")).toLowerCase();
    if (b.refusal) ligne.className = "en-echec";
    const nom = element("strong", null, b.displayName);
    cellule(ligne, nom, b.boxId).querySelector(".sous-ligne").classList.add("mono");
    cellule(ligne, b.groups.length ? b.groups.join(", ") : "—");
    const [statut, genre] = STATUTS[b.status] || [b.status, "neutre"];
    cellule(ligne, pastille(statut, genre));
    if (b.applied == null) cellule(ligne, pastille("pas de compte rendu", "neutre"));
    else if (b.applied === 0) cellule(ligne, pastille("configuration locale", "neutre"));
    else cellule(ligne, pastille("n° " + b.applied, ""), courte(b.appliedAt, true));
    if (b.refusal) cellule(ligne, pastille("n° " + b.refused + " refusée", "refus"), b.refusal);
    else cellule(ligne, "—");
    cellule(ligne, courte(b.reportedAt));
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
  filtrerBox();

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
    label.append(case_, " groupe « " + g + " » ", element("span", "nombre", "(" + membres + " box)"));
    groupes.appendChild(label);
  }

  const versions = $("versions");
  versions.replaceChildren();
  if (!etat.documents.length) {
    versions.append(element("li", "discret", "Aucune version publiée : les box appliquent leur configuration locale."));
  }
  const n = derniere();
  for (const d of [...etat.documents].sort((a, b) => b.sequence - a.sequence)) {
    const enVigueur = d.sequence === n;
    const bloc = element("li", "version" + (enVigueur ? " en-vigueur" : ""));
    const texte = element("div", "version-texte");
    const titre = element("div", "ligne-titre");
    titre.append(element("strong", null, "n° " + d.sequence),
      element("span", "discret", courte(d.issuedAt, true) + (d.author ? " · par " + d.author : "")),
      pastille(enVigueur ? "en vigueur" : "remplacée", enVigueur ? "" : "neutre"));
    texte.append(titre);
    if (d.comment) texte.append(element("span", null, "« " + d.comment + " »"));
    const portees = element("div", "portees");
    const portee = (t, genre) => portees.append(element("span", "portee" + (genre ? " " + genre : ""), t));
    portee(d.target.all ? "toute l'organisation" : [
      (d.target.boxes || []).length ? d.target.boxes.length + " box" : null,
      (d.target.groups || []).length ? "groupe" + (d.target.groups.length > 1 ? "s " : " ") + d.target.groups.join(", ") : null,
    ].filter(Boolean).join(" et "), "cible");
    if (d.priority) portee("priorité : " + d.priority.rules.length + " règle" + (d.priority.rules.length > 1 ? "s" : ""));
    else portee("priorité locale", "locale");
    if (d.routing) portee("routage : " + d.routing.rules.length + " règle" + (d.routing.rules.length > 1 ? "s" : ""));
    else portee("routage local", "locale");
    if (d.dicom) {
      const noeuds = (d.dicom.nodes || []).length;
      const routes = (d.dicom.outgoing || []).length + (d.dicom.incoming || []).length;
      portee("DICOM : " + noeuds + " nœud" + (noeuds > 1 ? "s" : "") + ", " + routes + " route" + (routes > 1 ? "s" : ""));
    }
    texte.append(portees);
    const reprendre = element("button", "secondaire", "Reprendre comme brouillon");
    reprendre.type = "button";
    reprendre.setAttribute("aria-label", "Reprendre comme brouillon la version n° " + d.sequence);
    reprendre.addEventListener("click", () => reprendreVersion(d));
    bloc.append(texte, reprendre);
    versions.appendChild(bloc);
  }
  $("compte-versions").textContent = etat.documents.length
    ? etat.documents.length + " version" + (etat.documents.length > 1 ? "s" : "") : "";
}

function reprendreVersion(d) {
  $("priorite").value = d.priority ? JSON.stringify(d.priority, null, 2) : "";
  $("routage").value = d.routing ? JSON.stringify(d.routing, null, 2) : "";
  $("dicom").value = d.dicom ? JSON.stringify(d.dicom, null, 2) : "";
  document.querySelector("input[name=cible][value=" + (d.target.all ? "toutes" : "liste") + "]").checked = true;
  for (const c of $("choix-box").querySelectorAll("input")) c.checked = !d.target.all && (d.target.boxes || []).includes(c.value);
  for (const c of $("choix-groupes").querySelectorAll("input")) c.checked = !d.target.all && (d.target.groups || []).includes(c.value);
  $("motif").value = "";
  $("prepare").hidden = true;
  ongletActif("brouillon");
  message("Version n° " + d.sequence + " reprise comme brouillon : la modifier, puis préparer un nouveau document.", "ok");
  $("priorite").focus();
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
    dicom: lireJson("dicom", "Configuration DICOM"),
  };
}

async function charger() {
  try {
    etat = await api("GET", "/admin/api/state");
    afficher();
    afficherIndicateurs(null);
    afficherDemandes(await api("GET", "/directory/v1/enrollment-requests"));
  } catch (e) {
    message("Lecture de l'état impossible : " + e.message, "erreur");
  }
}

function afficherDemandes(demandes) {
  const liste = $("demandes");
  liste.replaceChildren();
  for (const d of demandes) {
    const li = element("li", "carte-element" + (d.withCode ? "" : " attention"));
    const tete = element("div", "entete");
    tete.append(element("h4", null, d.displayName),
      d.withCode ? pastille("code d'enrôlement joint", "") : pastille("sans code : comparer l'empreinte", "attention"));
    li.append(tete, element("span", "discret mono", d.boxId + " · reçue " + courte(d.receivedAt)),
      element("span", "empreinte", d.fingerprint),
      d.withCode ? element("span", "discret", "Code vérifié par l'outil du responsable : validation en un clic de son côté.")
        : element("span", "attention-texte", "À traiter à la main par le responsable, après comparaison de l'empreinte avec l'installateur."));
    liste.appendChild(li);
  }
  if (!demandes.length) liste.append(element("li", "discret", "Aucune demande en attente."));
  $("compte-demandes").textContent = demandes.length ? demandes.length + " demande" + (demandes.length > 1 ? "s" : "") : "";
  afficherIndicateurs(demandes);
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
      "# puis le publier (« Publier et versions »), ou :\n" +
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
    // Résultat en étiquettes (priorité, destinations), annoncé comme une phrase : vidé puis rempli (RGAA 7.5).
    const p = $("resultat");
    const morceaux = [];
    const etiquette = (t) => element("span", "valeur-essai", t);
    if (r.priority) morceaux.push(etiquette("priorité " + r.priority), element("span", null, r.priorityRule ? "règle « " + r.priorityRule + " »" : "par défaut"));
    morceaux.push(element("span", "separateur", "·"));
    if (r.routingRule) {
      morceaux.push(element("span", null, "routage vers"), ...r.destinations.map(etiquette), element("span", null, "règle « " + r.routingRule + " »"));
    } else {
      morceaux.push(element("span", null, "aucune règle de routage ne correspond"));
    }
    clearTimeout(minuteries.get(p));
    p.replaceChildren();
    minuteries.set(p, setTimeout(() => p.replaceChildren(...morceaux), 50));
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
    afficherFichier();
    await charger();
  } catch (e) {
    message("Publication refusée : " + e.message, "erreur");
  }
}

function afficherFichier() {
  const fichier = $("fichier").files[0];
  $("fichier-nom").textContent = fichier ? fichier.name : "Aucun document choisi";
  $("fichier-detail").textContent = fichier
    ? "Document signé (JSON) · " + Math.max(1, Math.round(fichier.size / 1024)) + " Ko : le service revérifiera sa signature."
    : "Document signé (JSON), produit par configuration-signer.";
}

async function copierCommandes() {
  try {
    await navigator.clipboard.writeText($("commandes").textContent);
    message("Commandes copiées dans le presse-papiers.", "ok");
  } catch (e) {
    message("Copie impossible : sélectionner les commandes à la main.", "erreur");
  }
}

document.addEventListener("DOMContentLoaded", () => {
  for (const b of document.querySelectorAll("[data-onglet]")) b.addEventListener("click", () => ongletActif(b.dataset.onglet, true));
  window.addEventListener("hashchange", () => ongletActif(location.hash.slice(1)));
  ongletActif(location.hash.slice(1));
  $("recherche").addEventListener("input", filtrerBox);
  $("fichier").addEventListener("change", afficherFichier);
  $("copier-commandes").addEventListener("click", copierCommandes);
  $("rafraichir").addEventListener("click", charger);
  $("preparer").addEventListener("click", preparer);
  $("essayer").addEventListener("click", essayer);
  $("publier").addEventListener("click", publier);
  charger();
});
