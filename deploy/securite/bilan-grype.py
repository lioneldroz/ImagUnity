#!/usr/bin/env python3
"""Bilan des analyses Grype (images du site central, système d'une box) : rapport Markdown et décision.

Usage : bilan-grype.py [--rapport-seul NOM …] NOM=rapport.json [NOM=rapport.json …]

Bloquant : une faille « High » ou « Critical » qui a un correctif publié, dans une cible que nous construisons
(images du site central) : reconstruire l'image suffit à la corriger.
Signalé sans bloquer :
  - les cibles en rapport seul (--rapport-seul), comme le système d'une box : il dépend des mises à jour de la
    distribution, que la box installe d'elle-même ; un correctif publié en amont (Red Hat) peut ne pas l'être encore
    par Rocky ;
  - les failles « High » ou « Critical » sans correctif publié ;
  - les exceptions appliquées (.grype.yaml).
Les rapports sont ceux de `grype … -o json`.
"""
import json, sys

GRAVES = ("Critical", "High")


def ligne(cible, m):
    v, a = m["vulnerability"], m["artifact"]
    fix = ", ".join(v.get("fix", {}).get("versions") or []) or "—"
    return f"| {cible} | {v['id']} | {v.get('severity', '?')} | {a['name']} {a.get('version', '')} | {a.get('type', '')} | {fix} |"


def main(args):
    bloquants, a_suivre, sans_correctif, ignores = [], [], [], []
    rapport_seul = set()
    while args and args[0] == "--rapport-seul":
        rapport_seul.add(args[1])
        args = args[2:]
    for arg in args:
        cible, _, chemin = arg.partition("=")
        rapport = json.load(open(chemin, encoding="utf-8"))
        for m in rapport.get("matches", []):
            v = m["vulnerability"]
            if v.get("severity") not in GRAVES:
                continue
            if v.get("fix", {}).get("state") != "fixed":
                sans_correctif.append(ligne(cible, m))
            else:
                (a_suivre if cible in rapport_seul else bloquants).append(ligne(cible, m))
        for m in rapport.get("ignoredMatches", []):
            ignores.append(ligne(cible, m))
    entete = ["| Cible | Faille | Gravité | Composant | Type | Corrigée en |", "|---|---|---|---|---|---|"]
    sortie = ["## Images et système (Grype)", ""]
    if bloquants:
        sortie += ["### À corriger : correctif publié", ""] + entete + sorted(set(bloquants)) + [""]
    else:
        sortie += ["Aucune faille « High » ou « Critical » corrigible.", ""]
    if a_suivre:
        sortie += ["### Correctif publié en amont, cibles en rapport seul (non bloquant)", "",
                   "Mises à jour de la distribution : la box les installe d'elle-même dès qu'elles sont publiées "
                   "(dnf-automatic) ; un correctif de Red Hat peut ne pas l'être encore par Rocky.", ""]
        sortie += entete + sorted(set(a_suivre)) + [""]
    if sans_correctif:
        sortie += ["### Sans correctif publié (non bloquant, à suivre)", ""] + entete + sorted(set(sans_correctif)) + [""]
    if ignores:
        sortie += ["### Exceptions appliquées (.grype.yaml)", ""] + entete + sorted(set(ignores)) + [""]
    print("\n".join(sortie))
    return 1 if bloquants else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
