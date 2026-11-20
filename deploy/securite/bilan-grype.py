#!/usr/bin/env python3
"""Bilan des analyses Grype (images du site central, système d'une box) : rapport Markdown et décision.

Usage : bilan-grype.py NOM=rapport.json [NOM=rapport.json …]

Bloquant : une faille « High » ou « Critical » qui a un correctif publié (mettre à jour l'image ou le paquet suffit).
Signalé sans bloquer : les failles « High » ou « Critical » sans correctif encore publié, et les exceptions
appliquées (.grype.yaml). Les rapports sont ceux de `grype … -o json`.
"""
import json, sys

GRAVES = ("Critical", "High")


def ligne(cible, m):
    v, a = m["vulnerability"], m["artifact"]
    fix = ", ".join(v.get("fix", {}).get("versions") or []) or "—"
    return f"| {cible} | {v['id']} | {v.get('severity', '?')} | {a['name']} {a.get('version', '')} | {a.get('type', '')} | {fix} |"


def main(args):
    bloquants, sans_correctif, ignores = [], [], []
    for arg in args:
        cible, _, chemin = arg.partition("=")
        rapport = json.load(open(chemin, encoding="utf-8"))
        for m in rapport.get("matches", []):
            v = m["vulnerability"]
            if v.get("severity") not in GRAVES:
                continue
            (bloquants if v.get("fix", {}).get("state") == "fixed" else sans_correctif).append(ligne(cible, m))
        for m in rapport.get("ignoredMatches", []):
            ignores.append(ligne(cible, m))
    entete = ["| Cible | Faille | Gravité | Composant | Type | Corrigée en |", "|---|---|---|---|---|---|"]
    sortie = ["## Images et système (Grype)", ""]
    if bloquants:
        sortie += ["### À corriger : correctif publié", ""] + entete + sorted(set(bloquants)) + [""]
    else:
        sortie += ["Aucune faille « High » ou « Critical » corrigible.", ""]
    if sans_correctif:
        sortie += ["### Sans correctif publié (non bloquant, à suivre)", ""] + entete + sorted(set(sans_correctif)) + [""]
    if ignores:
        sortie += ["### Exceptions appliquées (.grype.yaml)", ""] + entete + sorted(set(ignores)) + [""]
    print("\n".join(sortie))
    return 1 if bloquants else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
