// Moteur des films ImagUnity : une scène SVG 1920×1080 dont l'image est une fonction pure du temps.
// Chaque film décrit ses plans ; rendu.js avance le temps image par image et capture chaque image.
"use strict";

const NS = "http://www.w3.org/2000/svg";
const C = {
  fond: "#F6F7F4", carte: "#FDFDFB", texte: "#1B2433", discret: "#4A5568", trait: "#3A4556", bord: "#C9D2DC",
  box: "#2563A8", boxFond: "#DCE8F5", gris: "#E3E6EA", chiffre: "#1F8A4C", chiffreFond: "#DDF1E4",
  signe: "#C26A00", signeFond: "#FBEBD3", refus: "#B42318", refusFond: "#FBE0DD", relais: "#6B4FA0",
  relaisFond: "#EAE3F5", annuaire: "#0F766E", annuaireFond: "#D9F0EE",
};

// --- Temps ---
const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
const ease = (x) => { x = clamp(x); return x < 0.5 ? 2 * x * x : 1 - Math.pow(-2 * x + 2, 2) / 2; };
/** Avancement 0→1 entre t0 et t1 (adouci). */
const prog = (t, t0, t1) => ease((t - t0) / (t1 - t0));
/** Opacité : apparaît en `fondu` s à partir de t0, disparaît avant t1 (Infinity : reste). */
const vis = (t, t0, t1 = Infinity, fondu = 0.4) =>
  t < t0 || t > t1 ? 0 : Math.min(clamp((t - t0) / fondu), t1 === Infinity ? 1 : clamp((t1 - t) / fondu));
const lerp = (a, b, p) => a + (b - a) * p;
const along = (points, p) => {
  // Position le long d'une ligne brisée, p de 0 à 1.
  const seg = [];
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    const d = Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]);
    seg.push(d);
    total += d;
  }
  let rest = clamp(p) * total;
  for (let i = 0; i < seg.length; i++) {
    if (rest <= seg[i] || i === seg.length - 1) {
      const q = seg[i] === 0 ? 0 : rest / seg[i];
      return [lerp(points[i][0], points[i + 1][0], q), lerp(points[i][1], points[i + 1][1], q)];
    }
    rest -= seg[i];
  }
  return points[points.length - 1];
};

// --- SVG ---
function el(name, attrs = {}, children = []) {
  const e = document.createElementNS(NS, name);
  for (const [k, v] of Object.entries(attrs)) {
    if (v !== undefined && v !== null) e.setAttribute(k, v);
  }
  for (const c of [].concat(children)) {
    if (c === null || c === undefined || c === false) continue;
    e.append(typeof c === "string" ? document.createTextNode(c) : c);
  }
  return e;
}
const g = (attrs, children) => el("g", attrs, children);
function text(x, y, s, o = {}) {
  const lines = String(s).split("\n");
  const size = o.size || 28;
  return el("text", {
    x, y, "font-size": size, "font-weight": o.weight || 400, fill: o.fill || C.texte,
    "text-anchor": o.anchor || "middle", "font-family": o.mono ? "DejaVu Sans Mono, monospace" : "Inter, sans-serif",
    opacity: o.opacity,
  }, lines.map((l, i) => el("tspan", { x, dy: i === 0 ? 0 : size * 1.25 }, l)));
}

// --- Pictogrammes ---
/** Site (établissement, site central) : cadre titré. */
function site(x, y, w, h, titre, o = {}) {
  return g({ opacity: o.opacity }, [
    el("rect", { x, y, width: w, height: h, rx: 22, fill: C.carte, stroke: o.stroke || C.bord, "stroke-width": 2 }),
    text(x + w / 2, y + 42, titre, { size: 26, weight: 700, fill: o.fill || C.texte }),
  ]);
}
function pacs(x, y, label = "PACS", o = {}) {
  return g({ opacity: o.opacity }, [
    el("ellipse", { cx: x, cy: y - 45, rx: 80, ry: 18, fill: "#CBD2DA", stroke: C.trait, "stroke-width": 2 }),
    el("path", { d: `M${x - 80},${y - 45} v80 a80,18 0 0 0 160,0 v-80`, fill: C.gris, stroke: C.trait, "stroke-width": 2 }),
    el("path", { d: `M${x - 80},${y - 5} a80,18 0 0 0 160,0`, fill: "none", stroke: C.trait, "stroke-width": 2 }),
    text(x, y + 75, label, { size: 24, weight: 600 }),
  ]);
}
function box(x, y, label, o = {}) {
  const fill = o.fond || C.boxFond;
  const stroke = o.couleur || C.box;
  return g({ opacity: o.opacity }, [
    el("rect", { x: x - 110, y: y - 60, width: 220, height: 120, rx: 18, fill, stroke, "stroke-width": 4 }),
    el("circle", { cx: x - 80, cy: y - 32, r: 7, fill: o.voyant || C.chiffre }),
    text(x, y + 10, label, { size: 30, weight: 700, fill: stroke }),
    o.sous ? text(x, y + 42, o.sous, { size: 18, fill: C.discret }) : null,
  ]);
}
function relais(x, y, label = "Relais", o = {}) {
  return g({ opacity: o.opacity }, [
    el("rect", { x: x - 130, y: y - 75, width: 260, height: 150, rx: 18, fill: C.relaisFond, stroke: C.relais, "stroke-width": 4 }),
    ...[0, 1, 2].map((i) => el("rect", { x: x - 100, y: y - 52 + i * 30, width: 200, height: 20, rx: 5, fill: "#fff", stroke: C.relais, "stroke-width": 2 })),
    text(x, y + 110, label, { size: 26, weight: 700, fill: C.relais }),
  ]);
}
function annuaire(x, y, label = "Annuaire", o = {}) {
  return g({ opacity: o.opacity }, [
    el("rect", { x: x - 85, y: y - 70, width: 170, height: 140, rx: 10, fill: C.annuaireFond, stroke: C.annuaire, "stroke-width": 4 }),
    el("line", { x1: x - 55, y1: y - 70, x2: x - 55, y2: y + 70, stroke: C.annuaire, "stroke-width": 4 }),
    ...[0, 1, 2, 3].map((i) => el("line", { x1: x - 35, y1: y - 40 + i * 25, x2: x + 60, y2: y - 40 + i * 25, stroke: C.annuaire, "stroke-width": 3 })),
    text(x, y + 105, label, { size: 26, weight: 700, fill: C.annuaire }),
  ]);
}
/** Personne (responsable, installateur). */
function personne(x, y, label, o = {}) {
  const c = o.couleur || C.trait;
  return g({ opacity: o.opacity }, [
    el("circle", { cx: x, cy: y - 45, r: 26, fill: o.fond || "#fff", stroke: c, "stroke-width": 4 }),
    el("path", { d: `M${x - 48},${y + 45} q0,-62 48,-62 q48,0 48,62 z`, fill: o.fond || "#fff", stroke: c, "stroke-width": 4 }),
    text(x, y + 85, label, { size: 22, weight: 600, fill: c }),
  ]);
}
function cle(x, y, o = {}) {
  const c = o.couleur || C.signe;
  return g({ opacity: o.opacity, transform: `translate(${x},${y}) scale(${o.echelle || 1})` }, [
    el("circle", { cx: -18, cy: 0, r: 16, fill: "none", stroke: c, "stroke-width": 6 }),
    el("path", { d: "M-2,0 H34 M22,0 V12 M30,0 V9", stroke: c, "stroke-width": 6, fill: "none", "stroke-linecap": "round" }),
  ]);
}
function cadenas(x, y, o = {}) {
  const c = o.couleur || C.chiffre;
  return g({ opacity: o.opacity, transform: `translate(${x},${y}) scale(${o.echelle || 1})` }, [
    el("path", { d: "M-14,-6 V-18 a14,14 0 0 1 28,0 V-6", fill: "none", stroke: c, "stroke-width": 5 }),
    el("rect", { x: -20, y: -8, width: 40, height: 30, rx: 5, fill: c }),
  ]);
}
function sceau(x, y, o = {}) {
  const c = o.couleur || C.signe;
  return g({ opacity: o.opacity, transform: `translate(${x},${y}) scale(${o.echelle || 1})` }, [
    el("circle", { r: 20, fill: c }),
    el("path", { d: "M-9,0 l6,7 l12,-14", stroke: "#fff", "stroke-width": 5, fill: "none", "stroke-linecap": "round" }),
  ]);
}
function croix(x, y, o = {}) {
  return g({ opacity: o.opacity, transform: `translate(${x},${y}) scale(${o.echelle || 1})` }, [
    el("circle", { r: 30, fill: C.refus }),
    el("path", { d: "M-12,-12 L12,12 M12,-12 L-12,12", stroke: "#fff", "stroke-width": 7, "stroke-linecap": "round" }),
  ]);
}
/** Document : examen DICOM (images), paquet chiffré, message signé, entrée d'annuaire. */
function document_(x, y, kind, o = {}) {
  const styles = {
    examen: [C.gris, C.trait], paquet: [C.chiffreFond, C.chiffre], message: [C.signeFond, C.signe],
    entree: [C.annuaireFond, C.annuaire], demande: [C.boxFond, C.box], code: ["#fff", C.trait],
  };
  const [fill, stroke] = styles[kind] || styles.examen;
  const s = o.echelle || 1;
  return g({ opacity: o.opacity, transform: `translate(${x},${y}) scale(${s})` }, [
    el("path", { d: "M-34,-44 H18 L34,-28 V44 H-34 Z", fill, stroke, "stroke-width": 4 }),
    kind === "examen" ? g({}, [0, 1, 2, 3].map((i) => el("rect", { x: -24 + (i % 2) * 26, y: -30 + Math.floor(i / 2) * 28, width: 22, height: 22, fill: "#9AA5B1" }))) : null,
    kind === "paquet" ? cadenas(0, 6, { echelle: 0.8 }) : null,
    kind === "message" ? sceau(0, 6, { echelle: 0.8 }) : null,
    kind === "entree" ? sceau(0, 6, { echelle: 0.8, couleur: C.annuaire }) : null,
    kind === "demande" ? sceau(0, 6, { echelle: 0.8, couleur: C.box }) : null,
    kind === "code" ? text(0, 12, "A7Y2", { size: 18, mono: true, weight: 700 }) : null,
    o.label ? text(0, 74, o.label, { size: 20, fill: stroke, weight: 600 }) : null,
  ]);
}
/** Morceau (fragment chiffré d'un paquet). */
function morceau(x, y, o = {}) {
  return el("rect", { x: x - 16, y: y - 16, width: 32, height: 32, rx: 5, fill: C.chiffre, opacity: o.opacity, stroke: "#fff", "stroke-width": 2 });
}
function fleche(points, o = {}) {
  const d = "M" + points.map((p) => p.join(",")).join(" L");
  return el("path", { d, fill: "none", stroke: o.couleur || C.trait, "stroke-width": o.epaisseur || 4,
    "stroke-dasharray": o.pointille ? "12 10" : null, "marker-end": o.sansPointe ? null : "url(#pointe)", opacity: o.opacity });
}
function bulle(x, y, s, o = {}) {
  const lines = String(s).split("\n");
  const size = o.size || 24;
  const w = o.largeur || Math.max(...lines.map((l) => l.length)) * size * 0.56 + 40;
  const h = lines.length * size * 1.25 + 28;
  return g({ opacity: o.opacity }, [
    el("rect", { x: x - w / 2, y: y - h / 2, width: w, height: h, rx: 14, fill: o.fond || "#fff", stroke: o.couleur || C.bord, "stroke-width": 3 }),
    text(x, y - h / 2 + 14 + size * 0.95, s, { size, fill: o.texte || C.texte, weight: o.weight || 500, mono: o.mono }),
  ]);
}

// --- Film ---
/**
 * Déclare un film : titre, durée (s), sous-titres [[début, fin, texte]], et plans [{de, a, dessin(t, p)}] où
 * dessin rend des éléments SVG pour l'instant t (p : avancement dans le plan, 0→1).
 */
function film(def) {
  const svg = el("svg", { xmlns: NS, viewBox: "0 0 1920 1080", width: 1920, height: 1080 });
  svg.append(el("defs", {}, [el("marker", { id: "pointe", viewBox: "0 0 10 10", refX: 8, refY: 5, markerWidth: 6, markerHeight: 6, orient: "auto-start-reverse" },
    [el("path", { d: "M0,0 L10,5 L0,10 z", fill: C.trait })])]));
  const scene = g({});
  svg.append(el("rect", { width: 1920, height: 1080, fill: C.fond }), scene);
  document.body.append(svg);

  function rendre(t) {
    scene.replaceChildren();
    for (const plan of def.plans) {
      if (t >= plan.de && t <= plan.a) {
        const p = (t - plan.de) / (plan.a - plan.de);
        scene.append(g({}, [].concat(plan.dessin(t, p)).filter(Boolean)));
      }
    }
    // Sous-titre courant (bandeau en bas).
    for (const [de, a, s] of def.sousTitres) {
      const o = vis(t, de, a, 0.3);
      if (o > 0) {
        const lines = s.split("\n");
        const h = 40 + lines.length * 44;
        scene.append(g({ opacity: o }, [
          el("rect", { x: 160, y: 1040 - h, width: 1600, height: h, rx: 18, fill: "#1B2433", opacity: 0.88 }),
          text(960, 1040 - h + 54, s, { size: 34, fill: "#fff", weight: 500 }),
        ]));
      }
    }
    // Filigrane discret.
    scene.append(text(1890, 40, "ImagUnity — données synthétiques", { size: 18, fill: C.discret, anchor: "end", opacity: 0.7 }));
  }
  window.FILM = { titre: def.titre, duree: def.duree, sousTitres: def.sousTitres, rendre };
  rendre(0);
}

Object.assign(window, { C, clamp, ease, prog, vis, lerp, along, el, g, text, site, pacs, box, relais, annuaire,
  personne, cle, cadenas, sceau, croix, document_, morceau, fleche, bulle, film });
