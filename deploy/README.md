# Environnement de test

Environnement Docker Compose de la preuve de concept : deux établissements, deux box, deux relais, sans aucun chemin réseau direct entre les sites.

```
 site-a                        relais                         site-b
┌──────────────┐   ┌───────────────────────────────┐   ┌──────────────┐
│ orthanc-a    │   │ smp-server      xftp-server   │   │ orthanc-b    │
│      │       │   │      ▲               ▲        │   │      ▲       │
│      ▼       │   │      │               │        │   │      │       │
│ box-a+chat-a ├───┼──────┴───────────────┴────────┼───┤ box-b+chat-b │
└──────────────┘   └───────────────────────────────┘   └──────────────┘
```

## Démarrage

```bash
cd deploy
docker compose up -d --build
```

| Service | URL |
|---|---|
| Console box A | http://localhost:8081 (API : `/api/v1/box`) |
| Console box B | http://localhost:8082 |
| Orthanc A (émetteur) | http://localhost:8042 |
| Orthanc B (destinataire) | http://localhost:8043 |

## Points d'attention

- Les relais génèrent leurs clés et leur empreinte au premier démarrage. Les clients `simplex-chat` lisent ces empreintes dans les volumes de configuration des relais (voir [simplex-chat/entrypoint.sh](simplex-chat/entrypoint.sh)).
- Chaque box est composée de deux conteneurs qui partagent la même pile réseau : `chat-x` (client SimpleX non modifié, AGPL-3.0) et `box-x` (logiciel ImagUnity, qui pilote le client par WebSocket sur `localhost:5225`).
- Orthanc A déclare la destination DICOM `IU_TO_B` : envoyer un examen à cette destination désigne la box B comme destinataire. L'entrée DICOM de la box arrive en phase 2.
- La dégradation de la liaison (débit, latence, pertes) sera ajoutée en phase 8 (Toxiproxy ou `tc netem`).
- **Données de test uniquement** : jeux publics ou anonymisés, jamais de patient réel.

## Réinitialisation

```bash
docker compose down -v
```
