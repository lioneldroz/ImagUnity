# Politique de sécurité d'ImagUnity

## Signaler une vulnérabilité

Ne pas ouvrir d'issue publique, ne pas joindre de données de patient. Écrire à l'adresse de sécurité de l'éditeur
**[adresse à compléter]**, avec :

- le composant touché (box, relais, service d'annuaire, outil du responsable, console) et sa version (`java -jar … --version`,
  ou la page d'accueil de la console) ;
- la description de la faille et, si possible, la manière de la reproduire sur le banc d'essai, avec des données
  synthétiques ;
- l'impact estimé.

Accusé de réception sous **3 jours ouvrés**, première qualification (gravité, versions touchées) sous **7 jours**.
L'auteur du signalement est tenu informé jusqu'à la correction et, s'il le souhaite, cité dans les notes de version.

## Versions suivies

| Version | Correctifs de sécurité |
|---|---|
| Dernière version livrée | Oui |
| Version précédente | Oui, pendant 3 mois après la sortie de la suivante |
| Versions plus anciennes | Non : mettre à jour |

## Délais de correction

Comptés depuis la publication de l'avis (CVE, avis GitHub ou de la distribution) ou depuis le signalement, pour une
faille qui touche ImagUnity tel qu'il est livré. La gravité est celle de l'avis (CVSS), revue au besoin selon
l'usage réel du composant dans ImagUnity.

| Gravité | Délai | Exemple de réponse |
|---|---|---|
| **Critique** (CVSS ≥ 9, ou exploitée activement) | **7 jours** | Version corrective, notes de version « à faire sans attendre » |
| **Haute** (7 à 8,9) | **30 jours** | Version corrective ou version suivante |
| **Moyenne** (4 à 6,9) | **90 jours** | Version suivante |
| **Basse** (< 4) | À la prochaine mise à jour des dépendances | — |

Quand le correctif dépend d'un tiers (Java, Rocky Linux, une bibliothèque) et n'existe pas encore, le délai court
à partir de sa publication. En attendant, ImagUnity publie une mesure de contournement quand elle existe, et une
déclaration VEX qui dit si la faille est atteignable dans ImagUnity.

## Ce qui est contrôlé en continu

Détail et procédure : [docs/reference/vulnerabilites.md](docs/reference/vulnerabilites.md).

- Vulnérabilités connues des bibliothèques (OSV-Scanner) et des images et systèmes livrés (Grype), à chaque push et
  chaque lundi ; une issue GitHub est ouverte si le contrôle du lundi échoue.
- Mises à jour des dépendances proposées chaque semaine (Dependabot).
- Secrets dans l'historique du dépôt (gitleaks), sécurité des workflows (zizmor), licences des composants.
