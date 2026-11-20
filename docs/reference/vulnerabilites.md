# Suivi des vulnérabilités

Cette page explique comment ImagUnity repère les vulnérabilités connues de ses composants, comment elles sont
traitées et dans quels délais. Les délais et la manière de signaler une faille sont dans [`SECURITY.md`](../../SECURITY.md).

## 1. Les contrôles

| Contrôle | Où | Ce qu'il voit | Quand | Bloquant |
|---|---|---|---|---|
| **OSV-Scanner** | `vulnerabilites.yml`, job « Dépendances » | Bibliothèques Java (nomenclature CycloneDX), npm, pip | Chaque push sur `main`, chaque pull request, chaque lundi | Oui, dès la moindre faille |
| **Grype** | `vulnerabilites.yml`, job « Images et système » | Images du relais et de l'annuaire, système d'une box Rocky Linux 10 (paquets, Java lui-même, jar) | Idem | Images : faille « High » ou « Critical » corrigible ; box : rapport seul |
| **Dependabot** | `.github/dependabot.yml` | Nouvelles versions Maven, npm, pip, images Docker et actions GitHub | Chaque lundi, et dès qu'un avis de sécurité paraît | — (propose des pull requests) |
| **gitleaks** | `ci.yml`, job « Secrets » | Clés, jetons, mots de passe dans tout l'historique du dépôt | Chaque push, chaque pull request | Oui |
| **zizmor** | `ci.yml`, job « Workflows GitHub » | Sécurité des workflows et de Dependabot : actions épinglées par empreinte, jeton non conservé, injections, caches | Chaque push, chaque pull request | Oui |
| **Java au démarrage** | `JavaRuntimeCheck`, installateur de la box, Dockerfile | Java 25 antérieur à 25.0.5.1 (CVE-2026-114938) | Démarrage, installation, construction | Installation et construction : oui ; démarrage : erreur dans le journal |

Le contrôle du lundi n'est lancé par personne : s'il échoue, le job « Alerte » ouvre une issue GitHub étiquetée
`vulnerabilite` (une seule, complétée à chaque nouvel échec tant qu'elle reste ouverte).

## 2. Réglages du dépôt (une fois)

Le dépôt est privé. Dans **Settings → Advanced Security** (ou *Code security*), activer :

- **Dependency graph** ;
- **Dependabot alerts** : alertes sur les dépendances vulnérables, même hors du contrôle du lundi ;
- **Dependabot security updates** : pull requests de correctif ouvertes d'elles-mêmes, sans attendre le lundi.

Les mises à jour de version (`dependabot.yml`) fonctionnent sans ces réglages.

## 3. Traiter une faille

1. **Qualifier** : le composant est-il livré ? La version touchée est-elle celle d'ImagUnity ? Un correctif
   existe-t-il ? La partie vulnérable est-elle atteignable dans ImagUnity ?
2. **Corriger dans le délai** de [`SECURITY.md`](../../SECURITY.md) : mettre à jour la dépendance (souvent la pull
   request de Dependabot), l'image de base ou le paquet du système ; à défaut, remplacer le composant.
3. **Livrer** : version corrective, et entrée dans les [notes de version](../guides/notes-de-version.md) avec ce
   qu'il faut faire sur les sites (« à faire sans attendre » pour une faille critique).
4. **Fermer l'issue** `vulnerabilite`, avec le lien de la correction.

**Pas d'exception par défaut.** `osv-scanner.toml` et `.grype.yaml` restent vides : une faille qui n'a pas encore de
correctif bloque la CI tant qu'elle n'est pas corrigée. Une exception ne s'ajoute qu'avec l'accord du responsable du
produit, avec sa justification et une date d'expiration (passée cette date, la CI échoue de nouveau).

## 4. Pull requests de Dependabot

- **Groupées** : une pull request par écosystème et par semaine pour les versions mineures et correctives ; une par
  version majeure, à relire.
- **Délai de 7 jours** avant de proposer une version qui vient de paraître : une version piégée est souvent retirée
  dans ce délai. Les correctifs de sécurité ne l'attendent pas.
- **NOTICE.md** : une mise à jour Maven ou npm change souvent la liste des composants livrés. Le job « Licences des
  composants » échoue alors et dépose le `NOTICE.md` régénéré dans l'artefact **`notice`** de l'exécution. Vérifier
  la licence des composants nouveaux (le job échoue aussi pour une licence refusée), puis committer ce fichier sur la
  branche de la pull request.
- Les images d'exécution restent sur **Ubuntu 24.04 LTS** : pas de passage automatique à une version majeure suivante.

## 5. Déclarations VEX

Une déclaration VEX (*Vulnerability Exploitability eXchange*) dit, pour une faille donnée, si un produit est touché.
Elle sert aux clients et aux auditeurs qui analysent eux-mêmes les images ou les jar d'ImagUnity, et retrouvent les
mêmes avis que la CI.

ImagUnity utilise le format **OpenVEX**. Une déclaration s'écrit quand une faille est signalée sur un composant
livré **sans toucher ImagUnity** (par exemple : fonction vulnérable jamais appelée, option désactivée) ou quand elle
est **en cours de correction** :

```json
{
  "@context": "https://openvex.dev/ns/v0.2.0",
  "@id": "https://imagunity.example/vex/2026-001",
  "author": "Éditeur d'ImagUnity",
  "timestamp": "2026-11-20T00:00:00Z",
  "version": 1,
  "statements": [
    {
      "vulnerability": { "name": "CVE-AAAA-NNNNN" },
      "products": [ { "@id": "pkg:oci/imagunity-relay?tag=1.4.0" } ],
      "status": "not_affected",
      "justification": "vulnerable_code_not_in_execute_path",
      "impact_statement": "La fonction touchée n'est jamais appelée par le relais."
    }
  ]
}
```

États possibles : `not_affected` (avec une justification), `affected` (avec la mesure à prendre), `fixed`,
`under_investigation`. Les déclarations seraient jointes à chaque livraison, à côté de `NOTICE.md`. Grype sait les
lire (`grype … --vex fichier.json`) ; **la CI ne s'en sert pas** pour masquer une faille : une déclaration
`not_affected` vaut exception et suit la même règle (accord du responsable du produit).

Aucune déclaration n'est publiée à ce jour.
