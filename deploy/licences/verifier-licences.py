#!/usr/bin/env python3
"""Contrôle des licences des composants tiers d'ImagUnity, logiciel propriétaire.

Chaque composant doit pouvoir être utilisé dans un produit commercial fermé : sa licence (ou l'une de ses licences,
quand il en propose plusieurs) doit être permissive, ou à copyleft faible (MPL, EPL, CDDL : seuls les fichiers du
composant restent sous sa licence). Une licence fortement contaminante (GPL, AGPL, LGPL sans alternative), inconnue ou
absente fait échouer le contrôle.

Sources lues :
  --sbom        nomenclature CycloneDX du backend (cyclonedx-maven-plugin) : composants livrés dans les jar ;
  --npm         package-lock.json d'un projet dont les dépendances de production sont livrées (console de la box) ;
  --npm-outil   package-lock.json d'un outil non livré (tests d'accessibilité) ;
  --pip-outil   requirements.txt d'un outil non livré (campagne de tests).
Sorties :
  --notice F    écrit la liste des composants livrés, avec la licence retenue et les obligations (NOTICE.md) ;
  --verifier-notice F  échoue si F n'est plus à jour (à régénérer avec --notice).
"""
import argparse, json, pathlib, re, sys

POLITIQUE = json.loads((pathlib.Path(__file__).parent / "politique.json").read_text(encoding="utf-8"))
PERMISSIVES = set(POLITIQUE["permissives"])
FAIBLES = set(POLITIQUE["copyleft_faible"])


def normaliser(nom):
    nom = nom.strip().strip("()")
    return POLITIQUE["noms"].get(nom, nom)


def alternatives(expression):
    """Une expression SPDX simple : « A OR B », « A AND B », ou une liste de licences (au choix) -> liste d'options,
    chaque option étant la liste des licences à respecter ensemble."""
    options = []
    for partie in re.split(r"\s+OR\s+", expression.strip().strip("()")):
        options.append([normaliser(x) for x in re.split(r"\s+AND\s+", partie)])
    return options


def retenir(options):
    """La meilleure option : permissive d'abord, sinon à copyleft faible ; None si aucune n'est acceptable."""
    for accepte in (PERMISSIVES, PERMISSIVES | FAIBLES):
        for option in options:
            if all(l in accepte for l in option):
                return " AND ".join(option)
    return None


class Controle:
    def __init__(self):
        self.livres, self.outils, self.erreurs = [], [], []

    def ajouter(self, nom, version, options, livre, origine):
        decl = POLITIQUE["declarees"].get(nom)
        if not options and decl:
            options = [[normaliser(decl["licence"])]]
        if not options:
            self.erreurs.append(f"{origine} : {nom}@{version} ne déclare pas de licence (la vérifier, puis la déclarer "
                                "dans deploy/licences/politique.json, rubrique « declarees »)")
            return
        retenue = retenir(options)
        toutes = " OR ".join(" AND ".join(o) for o in options)
        if retenue is None:
            self.erreurs.append(f"{origine} : {nom}@{version} sous « {toutes} » : licence non autorisée ou non reconnue "
                                "(voir docs/reference/licences.md)")
            return
        (self.livres if livre else self.outils).append((nom, version, retenue, toutes))

    def sbom(self, chemin):
        for c in json.loads(pathlib.Path(chemin).read_text(encoding="utf-8")).get("components", []):
            if c.get("group", "").startswith("com.imagunity"):
                continue  # ImagUnity lui-même
            options = []
            for l in c.get("licenses", []):
                if "expression" in l:
                    options += alternatives(l["expression"])
                elif "license" in l:
                    options.append([normaliser(l["license"].get("id") or l["license"].get("name", ""))])
            self.ajouter(f"{c.get('group')}:{c['name']}", c.get("version", ""), options, True, "backend")

    def npm(self, chemin, livre):
        projet = pathlib.Path(chemin).parent.name
        for cle, p in json.loads(pathlib.Path(chemin).read_text(encoding="utf-8"))["packages"].items():
            if not cle:
                continue  # le projet lui-même
            nom = cle.split("node_modules/")[-1]
            lic = p.get("license")
            options = alternatives(lic) if isinstance(lic, str) else []
            self.ajouter("npm:" + nom, p.get("version", ""), options, livre and not p.get("dev"), projet)

    def pip(self, chemin):
        for ligne in pathlib.Path(chemin).read_text(encoding="utf-8").splitlines():
            ligne = ligne.split("#")[0].strip()
            if ligne:
                nom, _, version = ligne.partition("==")
                self.ajouter("pypi:" + nom.strip().lower(), version.strip(), [], False, "campagne")

    def notice(self):
        lignes = ["# Composants tiers livrés avec ImagUnity", "",
                  "ImagUnity est un logiciel propriétaire (voir LICENSE). Il intègre les composants tiers ci-dessous, "
                  "chacun sous sa propre licence. Liste générée par `deploy/licences/verifier-licences.py` ; ne pas "
                  "modifier à la main.", "",
                  "Quand un composant propose plusieurs licences, la licence retenue est indiquée ; les autres ne "
                  "s'appliquent pas à ImagUnity.", ""]
        faibles = sorted({(n, v, r) for n, v, r, _ in self.livres if not all(l in PERMISSIVES for l in r.split(" AND "))})
        if faibles:
            lignes += ["## Composants sous licence à copyleft faible", "",
                       "Le code source de ces composants est disponible auprès de leurs auteurs. ImagUnity les utilise "
                       "sans modification ; une modification d'un de leurs fichiers devrait être publiée sous la même "
                       "licence.", ""]
            lignes += [f"- {n} {v} : {r}" for n, v, r in faibles] + [""]
        lignes += ["## Liste complète", "", "| Composant | Version | Licence retenue | Licences proposées |", "|---|---|---|---|"]
        for n, v, r, t in sorted(set(self.livres)):
            lignes.append(f"| {n} | {v} | {r} | {t if t != r else ''} |")
        return "\n".join(lignes) + "\n"


def main():
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("--sbom", action="append", default=[])
    a.add_argument("--npm", action="append", default=[])
    a.add_argument("--npm-outil", action="append", default=[])
    a.add_argument("--pip-outil", action="append", default=[])
    a.add_argument("--notice")
    a.add_argument("--verifier-notice")
    args = a.parse_args()
    c = Controle()
    for f in args.sbom: c.sbom(f)
    for f in args.npm: c.npm(f, True)
    for f in args.npm_outil: c.npm(f, False)
    for f in args.pip_outil: c.pip(f)

    compte = {}
    for _, _, r, _ in c.livres + c.outils:
        compte[r] = compte.get(r, 0) + 1
    print(f"{len(c.livres)} composants livrés, {len(c.outils)} composants d'outils non livrés.")
    for r, n in sorted(compte.items(), key=lambda x: -x[1]):
        print(f"  {n:4d}  {r}{'  (copyleft faible)' if not all(l in PERMISSIVES for l in r.split(' AND ')) else ''}")
    code = 0
    if c.erreurs:
        print("\nLicences refusées :")
        for e in c.erreurs:
            print("  - " + e)
        code = 1
    texte = c.notice()
    if args.notice:
        pathlib.Path(args.notice).write_text(texte, encoding="utf-8")
        print(f"\n{args.notice} écrit.")
    if args.verifier_notice:
        actuel = pathlib.Path(args.verifier_notice)
        if not actuel.exists() or actuel.read_text(encoding="utf-8") != texte:
            print(f"\n{args.verifier_notice} n'est plus à jour : le régénérer avec --notice {args.verifier_notice} "
                  "(mêmes sources), puis le committer.")
            code = 1
    return code


if __name__ == "__main__":
    sys.exit(main())
