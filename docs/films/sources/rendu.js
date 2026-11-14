#!/usr/bin/env node
// Rendu d'un film : avance le temps image par image dans la page du film (Chromium sans écran), capture chaque image
// et l'envoie à ffmpeg. Produit le MP4, l'image de couverture et les sous-titres (.srt).
//   node rendu.js 1-vue-ensemble [--fps 30] [--de 0 --a 10] [--planche]
"use strict";
const { chromium } = require("playwright");
const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");

const args = process.argv.slice(2);
const nom = args.find((a) => !a.startsWith("--"));
const option = (n, d) => { const i = args.indexOf("--" + n); return i >= 0 ? Number(args[i + 1]) : d; };
if (!nom) {
  console.error("Usage : node rendu.js <film> [--fps 30] [--de s] [--a s] [--planche]");
  process.exit(2);
}
const fps = option("fps", 30);
const sortie = path.resolve(__dirname, "..");

function srt(sousTitres) {
  const ts = (s) => {
    const ms = Math.round(s * 1000);
    const h = String(Math.floor(ms / 3600000)).padStart(2, "0");
    const m = String(Math.floor(ms / 60000) % 60).padStart(2, "0");
    const sec = String(Math.floor(ms / 1000) % 60).padStart(2, "0");
    return `${h}:${m}:${sec},${String(ms % 1000).padStart(3, "0")}`;
  };
  return sousTitres.map(([de, a, texte], i) => `${i + 1}\n${ts(de)} --> ${ts(a)}\n${texte}\n`).join("\n");
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  const erreurs = [];
  page.on("pageerror", (e) => erreurs.push(e.message));
  await page.goto("file://" + path.join(__dirname, nom + ".html"));
  await page.waitForFunction(() => window.FILM);
  await page.evaluate(() => document.fonts.ready);
  if (erreurs.length) throw new Error(erreurs.join("\n"));
  const film = await page.evaluate(() => ({ titre: FILM.titre, duree: FILM.duree, sousTitres: FILM.sousTitres, couverture: FILM.couverture }));
  const de = option("de", 0);
  const a = option("a", film.duree);

  if (args.includes("--planche")) {
    // Planche contact : une image toutes les 2 s, pour relecture.
    const dossier = path.join(__dirname, "planches", nom);
    fs.mkdirSync(dossier, { recursive: true });
    for (let t = de; t <= a; t += 2) {
      await page.evaluate((t) => FILM.rendre(t), t);
      await page.screenshot({ path: path.join(dossier, `${String(Math.round(t)).padStart(3, "0")}.png`) });
    }
    await browser.close();
    console.log("Planche : " + dossier);
    return;
  }

  fs.writeFileSync(path.join(sortie, nom + ".srt"), srt(film.sousTitres));
  await page.evaluate((t) => FILM.rendre(t), film.couverture ?? Math.min(8, film.duree / 2));
  await page.screenshot({ path: path.join(sortie, nom + ".jpg"), type: "jpeg", quality: 88 });

  const ffmpeg = spawn("ffmpeg", ["-y", "-loglevel", "error", "-f", "image2pipe", "-framerate", String(fps), "-c:v", "mjpeg",
    "-i", "-", "-c:v", "libx264", "-preset", "medium", "-tune", "animation", "-crf", "22", "-pix_fmt", "yuv420p",
    "-movflags", "+faststart", path.join(sortie, nom + ".mp4")], { stdio: ["pipe", "inherit", "inherit"] });
  const total = Math.round((a - de) * fps);
  const debut = Date.now();
  for (let i = 0; i <= total; i++) {
    const t = de + i / fps;
    await page.evaluate((t) => FILM.rendre(t), t);
    const image = await page.screenshot({ type: "jpeg", quality: 92 });
    if (!ffmpeg.stdin.write(image)) {
      await new Promise((r) => ffmpeg.stdin.once("drain", r));
    }
    if (i % (fps * 10) === 0) {
      process.stdout.write(`\r${film.titre} : ${Math.round(t)} / ${Math.round(a)} s (${Math.round((Date.now() - debut) / 1000)} s écoulées)`);
    }
  }
  ffmpeg.stdin.end();
  await new Promise((r, j) => ffmpeg.on("close", (c) => (c === 0 ? r() : j(new Error("ffmpeg : code " + c)))));
  await browser.close();
  if (erreurs.length) throw new Error(erreurs.join("\n"));
  console.log(`\n${nom}.mp4 : ${film.duree} s, ${total + 1} images`);
})().catch((e) => { console.error(e); process.exit(1); });
