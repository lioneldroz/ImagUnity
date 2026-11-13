# Cahier des charges — audit de sécurité externe d'ImagUnity

> Étape 7 de la [spécification IU-T](../reference/specification-protocole.md) : prérequis à la mise en production avec des données de patients.
> Document à remettre aux prestataires consultés, avec le [dossier d'audit](dossier-audit.md).

## 1. Contexte

ImagUnity transfère des examens d'imagerie médicale (DICOM) entre établissements de santé par des relais aveugles, avec un chiffrement de bout en bout hybride post-quantique (X25519 + ML-KEM-768, Ed25519 + ML-DSA-65) et un annuaire signé par le responsable de l'organisation. Le système est développé en Java 25 (Spring Boot 4), avec une console web React ; il se déploie par Docker Compose ou K3s. Un pilote avec des données réelles de patients est prévu après l'audit et la correction de ses constats.

## 2. Prestataire attendu

- **Qualification PASSI** de l'ANSSI, au moins pour les portées « audit d'architecture », « audit de code source », « audit de configuration » et « tests d'intrusion ».
- **Compétence démontrée en cryptographie appliquée** : audit de protocoles et de code cryptographique, connaissance des primitives post-quantiques normalisées (FIPS 203, FIPS 204) et des constructions hybrides. À justifier par des références (audits publiés, publications, membres de l'équipe). Un groupement ou une sous-traitance déclarée pour la partie cryptographique est accepté.
- Expérience du secteur de la santé appréciée (données de santé, hébergement HDS, DICOM).
- Équipe nommée dans l'offre, avec les CV ; tout changement soumis à accord.

## 3. Périmètre

Tout le système, détaillé au [dossier d'audit § 2 et § 6](dossier-audit.md) :

| Lot | Contenu | Livrable |
|---|---|---|
| 1. Conception cryptographique | Suite `IU1`, construction hybride, documents signés, anti-rejeu, anti-retour en arrière, chiffrement applicatif, protection des clés (phrase de passe, TPM), mises à jour signées ; spécifications fournies | Avis motivé, constats |
| 2. Revue de code source | Modules sensibles en priorité : `protocol`, `directory`, `packaging`, `transport`, `relay`, `release` ; puis `orchestration`, `rules`, `dicom`, `configuration`, `api`, `app`, `directory-server`, console | Constats avec emplacement dans le code |
| 3. Tests d'intrusion boîte grise | Pile d'essai fournie (box, relais simple et réparti, annuaire, interface d'administration) ; scénarios : relais compromis, annuaire compromis, box compromise, attaquant sur le réseau du site, attaquant Internet | Constats, preuves de concept |
| 4. Configuration et déploiement | Images, Compose, manifestes K3s, chaîne d'intégration continue | Constats, recommandations de durcissement |

Hors périmètre : PACS et postes des sites, hébergement physique, procédures organisationnelles (sauf avis sur la gestion des clés du responsable et de l'éditeur, bienvenu).

## 4. Conditions de réalisation

- **Données exclusivement synthétiques.** Aucune donnée réelle de patient n'est mise à disposition ni ne doit être utilisée.
- Accès au dépôt de code (lecture) au commit de référence fixé au lancement ; pile d'essai déployée par le projet ou par le prestataire (procédure fournie).
- Clés de test générées pour l'audit ; aucune clé de production n'est communiquée.
- Interlocuteur technique désigné côté projet, disponible pendant l'audit ; questions et réponses tracées.
- Réunion de lancement, point d'étape hebdomadaire, restitution.
- **Signalement immédiat** de tout constat critique, sans attendre le rapport.
- Confidentialité : constats et rapport confidentiels jusqu'à leur correction ; publication éventuelle d'une synthèse décidée d'un commun accord.

## 5. Livrables

1. **Rapport d'audit** : synthèse pour la direction ; constats classés par gravité avec score CVSS (v3.1 ou v4), description, preuve de concept quand c'est possible, impact, recommandation ; liste des points vérifiés sans constat.
2. **Avis sur la conception cryptographique** (lot 1), y compris sur les choix assumés du [dossier § 9](dossier-audit.md).
3. **Contre-audit** des corrections des constats critiques et élevés (inclus dans l'offre), avec rapport de clôture.

## 6. Charge indicative

À proposer par le prestataire. Ordre de grandeur attendu, pour comparaison des offres : lot 1, 5 à 8 jours ; lot 2, 10 à 15 jours ; lot 3, 6 à 10 jours ; lot 4, 2 à 3 jours ; contre-audit, 2 à 4 jours. Le code compte environ 19 000 lignes de Java (hors tests) et une console React légère.

## 7. Critères de choix

| Critère | Poids |
|---|---|
| Compétence cryptographique de l'équipe (références, profils) | 35 % |
| Méthodologie et couverture du périmètre | 25 % |
| Qualification PASSI et expérience santé | 15 % |
| Prix | 15 % |
| Délais et disponibilité, contre-audit | 10 % |

## 8. Calendrier indicatif

Consultation 3 semaines ; audit 4 à 6 semaines ; corrections ; contre-audit ; décision de mise en pilote.
