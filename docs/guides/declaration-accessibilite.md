# Déclaration d'accessibilité d'ImagUnity

> **Modèle à compléter par chaque organisation qui déploie ImagUnity.** L'obligation d'accessibilité (article 47 de la loi n° 2005-102 du 11 février 2005) pèse sur l'organisme qui met le service à disposition de ses agents ou du public : c'est lui qui publie la déclaration, sous son nom. L'éditeur fournit ce modèle et les résultats d'audit.
>
> Avant publication :
> 1. remplacer chaque `[à compléter]` ;
> 2. reporter l'état de conformité et le taux **issus d'un audit RGAA complet** (voir « État de conformité ») ;
> 3. vérifier ce texte contre le modèle officiel en vigueur sur [accessibilite.numerique.gouv.fr](https://accessibilite.numerique.gouv.fr/obligations/declaration-accessibilite/), qui peut avoir évolué ;
> 4. supprimer cet encadré.
>
> La déclaration se met à jour à chaque audit, et à chaque version d'ImagUnity qui change l'une des interfaces de façon notable (voir les [notes de version](notes-de-version.md)).

---

**[Nom de l'organisation]** s'engage à rendre ses services numériques accessibles, conformément à l'article 47 de la loi n° 2005-102 du 11 février 2005.

À cette fin, elle met en œuvre la stratégie et les actions suivantes :
- schéma pluriannuel de mise en accessibilité : [lien à compléter] ;
- plan d'actions de l'année en cours : [lien à compléter].

Cette déclaration d'accessibilité s'applique au **progiciel ImagUnity**, version **[à compléter, 0.15 ou suivante]**, et à ses trois interfaces web :

| Interface | Utilisée par | Où |
|---|---|---|
| Console de la box | exploitant de l'établissement | sur le réseau de l'établissement, adresse de la box |
| Interface d'administration de l'annuaire (`/admin/`) | responsable de l'organisation | site central de l'organisation |
| Interface locale du responsable | responsable de l'organisation | poste du responsable, ouverte par l'outil du responsable |

## État de conformité

ImagUnity est **[non conforme / partiellement conforme / totalement conforme — à compléter d'après l'audit]** avec le référentiel général d'amélioration de l'accessibilité (RGAA), version 4.1.2.

> *Note de rédaction, à supprimer.* La règle du RGAA :
> - **totalement conforme** : tous les critères applicables sont respectés ;
> - **partiellement conforme** : au moins 50 % des critères applicables sont respectés ;
> - **non conforme** : moins de 50 %, **ou aucun audit en cours de validité**.
>
> À la date de ce modèle, les trois interfaces ont fait l'objet d'un **audit interne** (relecture du code critère par critère, outils automatiques, mesures dans le navigateur). Les non-conformités relevées ont été corrigées, et un contrôle automatique de non-régression tourne à chaque modification (voir « Établissement de cette déclaration »). Cet audit interne **n'a pas comporté de tests avec les technologies d'assistance** (lecteurs d'écran) exigés par la méthode du RGAA. Il ne suffit donc pas, à lui seul, à établir un taux de conformité.
>
> Tant qu'un audit complet, avec ces tests, n'a pas été réalisé, l'état à déclarer est **« non conforme »**, avec la mention « aucun audit complet n'a encore été réalisé ». Après l'audit, reporter son état et son taux.

## Résultats des tests

L'audit de conformité réalisé par **[auditeur, interne ou externe — à compléter]** le **[date — à compléter]** révèle que :
- **[à compléter] %** des critères du RGAA version 4.1.2 sont respectés (taux moyen sur les trois interfaces) ;
- console de la box : [à compléter] % ;
- interface d'administration de l'annuaire : [à compléter] % ;
- interface locale du responsable : [à compléter] %.

## Contenus non accessibles

### Non-conformités

[À compléter d'après l'audit complet.]

L'audit interne du 18 novembre 2026 a relevé 31 non-conformités, réparties sur 18 critères différents, toutes corrigées depuis :
- 9 sur l'interface d'administration de l'annuaire ;
- 14 sur l'interface du responsable ;
- 8 sur la console de la box.

Aucune non-conformité connue ne reste ouverte à ce jour. Points relevés sans être retenus comme non-conformes, à soumettre à l'audit complet :
- console de la box : l'identifiant complet d'une étude et l'empreinte SHA-256 d'un paquet ne s'affichent en entier qu'au survol de la souris (attribut `title`) ;
- plusieurs boutons de même intitulé (« Relancer », « Valider », « Révoquer », « Renouveler », « Retirer », « Copier »), qui ne se distinguent que par leur ligne ou leur carte (critère 11.9) ;
- console de la box : conditions de priorité écrites avec les mots anglais « AND » et « OR » (critère 8.7) ;
- interface du responsable : le titre de la page ne change pas d'un onglet à l'autre.

### Dérogations pour charge disproportionnée

Aucune.

### Contenus non soumis à l'obligation d'accessibilité

Fichiers techniques produits ou lus par les interfaces : demande d'enrôlement, brouillons et documents signés de configuration (JSON), archives de sauvegarde. Ils sont destinés aux outils d'ImagUnity, pas à la lecture.

## Établissement de cette déclaration d'accessibilité

Cette déclaration a été établie le **[date de publication — à compléter]**.

### Technologies utilisées pour la réalisation des interfaces

- HTML5
- CSS
- JavaScript
- WAI-ARIA
- React (console de la box)

### Environnement de test

Audit interne (18 novembre 2026) :
- navigateur Chromium, en thème clair et en thème sombre ;
- affichage à 1 280 px et à 320 px de large ;
- texte agrandi à 200 % ;
- espacement du texte agrandi (critère 10.12).

Les vérifications de restitution par les technologies d'assistance restent à réaliser avec la base de référence du RGAA : [combinaisons lecteur d'écran et navigateur, et leurs versions — à compléter lors de l'audit complet, par exemple NVDA avec Firefox, JAWS avec Firefox, VoiceOver avec Safari].

### Outils pour évaluer l'accessibilité

- axe-core 4.14, limité aux règles rattachées au RGAA 4 et à WCAG 2.1 A et AA
- html-validate 11 (validité du code, en-têtes de tableau)
- Playwright 1.63, avec des mesures propres au projet : contrastes des contours de champs, messages de statut, affichage à 320 px, espacement du texte, parcours au clavier, focus, bulles d'aide
- relecture du code source, critère par critère

Ces contrôles automatiques tournent à chaque modification du code, dans l'intégration continue du projet (job « Accessibilité (RGAA 4.1.2) »). Ils couvrent une partie seulement des critères ; ils préviennent les régressions et ne remplacent pas un audit.

### Écrans ayant fait l'objet de la vérification de conformité

1. **Console de la box** : page unique, avec le détail des paquets d'un envoi ouvert et un appairage refusé.
2. **Interface d'administration de l'annuaire** : page unique, chargée, avec un document préparé, des règles essayées, un annuaire vide et un brouillon refusé.
3. **Interface locale du responsable** :
   - premier démarrage, sans organisation ;
   - onglets Tableau de bord, Démarrage, Demandes, Box, Configuration des box, Codes d'enrôlement, Partenaires et Aide ;
   - fenêtres de signature, de révocation et de confirmation.

## Retour d'information et contact

Si vous n'arrivez pas à accéder à un contenu ou à un service d'ImagUnity, vous pouvez contacter **[service ou personne responsable — à compléter]** pour être orienté vers une alternative accessible ou obtenir le contenu sous une autre forme :
- par courriel : [adresse — à compléter] ;
- par formulaire : [lien — à compléter] ;
- par courrier : [adresse postale — à compléter].

[Délai de réponse annoncé — à compléter, par exemple « Nous nous engageons à vous répondre sous 15 jours ».]

## Voies de recours

Cette procédure est à utiliser dans le cas suivant : vous avez signalé au responsable du service un défaut d'accessibilité qui vous empêche d'accéder à un contenu ou à un service, et vous n'avez pas obtenu de réponse satisfaisante.

Vous pouvez :
- écrire un message au Défenseur des droits, par le formulaire en ligne : <https://formulaire.defenseurdesdroits.fr/> ;
- contacter le délégué du Défenseur des droits dans votre région : <https://www.defenseurdesdroits.fr/saisir/delegues> ;
- envoyer un courrier par la poste (gratuit, ne pas mettre de timbre) :
  Défenseur des droits
  Libre réponse 71120
  75342 Paris CEDEX 07
