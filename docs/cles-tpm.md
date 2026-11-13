# Clés de la box protégées par le TPM

> Objectif (pilote, priorité 4 du [plan](plan-de-realisation.md)) : les clés d'identité de la box (signature et chiffrement) ne doivent pas pouvoir servir en dehors de la box. Un disque, une sauvegarde ou un volume copiés ne doivent rien livrer.
> Statut : **fait, par scellement dans le TPM**, et vérifié sur la pile Docker avec un TPM logiciel (§ 5).

## 1. Pourquoi un scellement, et pas des clés dans le TPM

Les clés de la box sont hybrides : Ed25519 et **ML-DSA-65** pour la signature, X25519 et **ML-KEM-768** pour le chiffrement. Les TPM 2.0 du marché ne savent ni créer ni utiliser des clés ML-DSA ou ML-KEM. Garder dans le TPM la seule moitié classique affaiblirait le principe hybride, qui veut que les deux algorithmes protègent ensemble.

Le TPM garde donc **le secret qui chiffre les fichiers de clés**. C'est le procédé de systemd-cryptenroll ou de Clevis pour les disques chiffrés :

```
premier démarrage : secret aléatoire de 256 bits ──scellé──► TPM de la box (lié aux PCR choisis)
                    clés de la box chiffrées par ce secret (PBKDF2 + AES-256-GCM, comme la phrase de passe)

chaque démarrage  : TPM ──descelle──► secret ──ouvre──► clés en mémoire
```

- **Le secret n'est connu de personne.** Il est tiré par la box, n'est écrit nulle part en clair, et seul le TPM de cette box peut le restituer.
- **Copiés ailleurs, les fichiers ne s'ouvrent pas.** Le secret scellé (`identite-box.json.tpm.pub` et `.tpm.priv`, droits 600) est chiffré par la clé de stockage du TPM. Un autre TPM le refuse (« integrity check failed »).
- **Lien à l'état de démarrage, à activer en production.** Sans PCR (réglage par défaut, pour les essais), un autre système démarré sur la même machine (clé USB) descelle le secret : la box l'écrit en avertissement au démarrage. Le PCR 7 (démarrage sécurisé) ne suffit pas seul : tout système signé par la même autorité UEFI (par exemple un shim signé par l'autorité tierce de Microsoft) produit la même valeur. Il faut mesurer aussi le chargeur et le noyau : `IMAGUNITY_DIRECTORY_IDENTITY_TPM_PCRS=sha256:0,4,7,8,9`, ou `sha256:7,11` avec un noyau unifié (UKI). La box avertit au démarrage si aucun de ces registres (4, 8, 9, 11) n'est lié. Une politique signée (`PolicyAuthorize`), qui survivrait aux mises à jour du noyau, est une suite possible (pré-audit, constat C4).
- **Pas de verrouillage par les coupures de courant.** Un TPM compte chaque arrêt non propre comme une tentative suspecte. Au-delà d'un seuil, il passe en verrouillage anti-force brute (« DA lockout ») et refuse les objets qu'il protège, parfois pendant des heures. La clé primaire et le secret scellé sont donc créés **hors de cette protection** (attribut `noda`). Ils n'ont pas de mot de passe à deviner. Le secret reste protégé par le TPM et, si voulu, par la politique PCR, qui demeure obligatoire.
- Le passage au matériel pour les clés elles-mêmes reste possible plus tard (TPM ou HSM prenant en charge ML-DSA et ML-KEM). `KeyFiles` reste le point de remplacement.

## 2. Configuration

| Variable | Rôle |
|---|---|
| `IMAGUNITY_DIRECTORY_IDENTITY_TPM` | `true` : clés protégées par le TPM |
| `IMAGUNITY_DIRECTORY_IDENTITY_TPM_PCRS` | Registres PCR auxquels lier le secret, par exemple `sha256:0,4,7,8,9` (vide : aucun, avec avertissement) |
| `TPM2TOOLS_TCTI` | Accès au TPM, par défaut `device:/dev/tpmrm0` |

L'image de la box contient `tpm2-tools`. `deploy/compose.tpm.yml` donne aux box l'accès au TPM de l'hôte (`/dev/tpmrm0`), en surcharge du Compose de base. Pour les essais sans TPM matériel, `deploy/campagne/compose.tpm-logiciel.yml` donne à chaque box un TPM logiciel (swtpm) dans son espace réseau.

**Jamais de repli silencieux.** Le TPM activé mais injoignable, ou qui refuse de desceller : la box ne démarre pas, avec un message explicite, par exemple « Le TPM refuse de desceller le secret des clés de la box (autre TPM, ou état de démarrage modifié)… ».

## 3. Migration d'une box en service

1. Ajouter `compose.tpm.yml` **en gardant** la phrase de passe (secret Docker) pour ce démarrage.
2. Au démarrage, la box tire le secret et le scelle. Elle ouvre ses clés avec la phrase de passe, les chiffre à nouveau par le secret, puis journalise : « Clés … désormais protégées par le TPM : la phrase de passe précédente ne sert plus, son secret peut être retiré ».
3. Retirer le secret Docker de la phrase de passe. L'identité et les entrées d'annuaire ne changent pas.

Une box en clair passe directement sous le TPM. Une box dont les clés sont chiffrées mais sans phrase de passe fournie refuse de démarrer et demande la phrase « une dernière fois ». Un arrêt pendant la migration est rattrapé au démarrage suivant.

## 4. Ce que cela change, et les limites

- **Protège** contre la copie du disque, du volume `/data` ou d'une sauvegarde. Avec les PCR, cela vaut aussi pour un démarrage modifié de la machine.
- **Ne protège pas** une box compromise en fonctionnement : le secret, puis les clés, sont en mémoire. Les parades restent la révocation dans l'annuaire et les mises à jour signées.
- **Perte du TPM = perte des clés.** C'est voulu : il n'existe aucune copie. Une carte mère remplacée, un TPM effacé, ou un changement de micrologiciel avec des PCR liés laissent la box sans identité. On crée alors une **nouvelle identité** (supprimer les fichiers `identite-box.json*`), on la fait enrôler, puis on révoque l'ancienne entrée. Les transferts en cours vers l'ancienne identité sont à relancer par l'émetteur.
- **Mise à jour du micrologiciel, du chargeur ou du noyau avec PCR liés** : les PCR 0, 4, 8 ou 9 changent, et la box ne descelle plus. Prévoir un réenrôlement (nouvelle identité) à chaque mise à jour de ces éléments, ou une politique signée.
- **Clé du responsable et clé de l'éditeur** : elles restent des fichiers chiffrés par phrase de passe, sur des postes hors ligne. Une carte à puce ou un HSM attend la prise en charge de ML-DSA.

**Versions antérieures.** Une box dont le secret a été scellé avant l'attribut `noda` le descelle avec l'ancien modèle de clé primaire, puis le scelle de nouveau au démarrage suivant. Le journal l'indique : « Secret scellé par une version antérieure : scellé de nouveau… ». Si son TPM est déjà en verrouillage, il faut le lever une fois, avec l'autorisation « lockout » de l'hôte (`tpm2_dictionarylockout --clear-lockout`).

Quand le TPM refuse, le message d'erreur reprend désormais la cause donnée par le TPM, et signale un verrouillage anti-force brute s'il y en a un.

## 5. Vérifié en réel (pile Docker, novembre 2026)

Avec un TPM logiciel (swtpm) par box et des PCR liés (`sha256:7`), sur la chaîne de la campagne de tests :
- **migration des box en service** : clés sous phrase de passe chiffrées sur place par le secret scellé, **même identité** (`db4a8efc-…`). Le transfert nominal donne **100 instances identiques sur 100** ;
- **redémarrage sans phrase de passe** (secret Docker retiré) : la box démarre, « Clés de la box protégées par le TPM » ;
- **état de démarrage modifié** (PCR 7 étendu) : la box refuse de démarrer, « Le TPM refuse de desceller… ». Après redémarrage du TPM (PCR remis à zéro, comme au redémarrage d'une machine), elle repart avec la même identité, son partenaire est vérifié, et un transfert donne 100/100 ;
- **secret scellé de B présenté au TPM de A** : refusé, « integrity check failed » ;
- **verrouillage anti-force brute** : pendant ces essais, les arrêts brutaux répétés des conteneurs avaient mis les deux TPM en verrouillage, et les box ne démarraient plus. Après correction :
  - les deux box ont scellé de nouveau leur secret au nouveau modèle, avec la même identité ;
  - le TPM de B a été mis volontairement en verrouillage (`inLockout: 1`), puis arrêté brutalement trois fois avec la box ;
  - B démarre malgré tout, et le transfert nominal donne **100/100** identiques.

Les tests automatiques couvrent la même logique avec un TPM simulé. Ils comprennent aussi un essai sur un vrai TPM logiciel quand swtpm est installé (sauté en CI).

## 6. Reste pour le pilote

- Essai sur un **TPM matériel** (`/dev/tpmrm0`), avec les PCR réels du parc.
- **Procédure de remplacement de matériel** outillée : nouvelle identité, enrôlement, révocation de l'ancienne.
- Clés elles-mêmes dans le matériel, dès que TPM et HSM prendront en charge ML-DSA et ML-KEM.
