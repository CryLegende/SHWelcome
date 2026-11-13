<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHWelcome

*/bienvenue : souhaiter la bienvenue aux nouveaux joueurs contre une petite récompense.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

/bienvenue : souhaiter la bienvenue aux nouveaux joueurs contre une petite récompense.

Utilisé sur : **Opale**.

## Dépendances

- Optionnelles : `SHLogs`, `SHNetwork` (plusieurs serveurs de jeu, voir plus bas)

## Limite quotidienne

`daily_limit` bienvenues récompensées par joueur et par jour. Le compteur repart de zéro **à minuit** (heure du serveur) et **survit aux redémarrages** : sans SHNetwork il est écrit dans `plugins/SHWelcome/compteurs.yml` (le jour en cours seulement, écrit à chaque bienvenue récompensée et à l'arrêt), avec SHNetwork il est en base (`welcome_daily`).

## Logs

Chaque bienvenue récompensée est écrite dans SHLogs, dans le fichier `bienvenues` (`log_file`). Le texte de la ligne se règle avec `reward_log_format` et la récompense affichée avec `reward_log_text`. Une bienvenue sans récompense (limite quotidienne atteinte) n'est pas écrite. Sans SHLogs, la ligne est écrite dans la console.

## Plusieurs serveurs de jeu (SHNetwork)

Sans SHNetwork, ou avec `reseau.actif: false`, rien ne change : chaque serveur annonce les joueurs qui arrivent pour la première fois chez lui et compte les accueils du jour dans `plugins/SHWelcome/compteurs.yml`.

Avec SHNetwork, le plugin décide pour tout le réseau :

- **Nouveau joueur** : un joueur jamais vu sur ce serveur n'est annoncé que si sa première connexion au réseau date de moins de `reseau.premiere-connexion.duree-max-minutes` (registre `Global.sh_track_firstseen`, tenu par SHPlayerTrack) et qu'aucun autre serveur ne l'a déjà annoncé (`INSERT IGNORE` dans `welcome_announced`). Un vieux compte qui découvre un serveur n'est donc pas accueilli, et un nouveau joueur qui passe d'un serveur à l'autre n'est annoncé qu'une fois.
- **Annonce** : le serveur qui gagne la réservation la publie sur le canal `reseau.canal` ; chaque serveur affiche le message cliquable à ses joueurs. Un `/bienvenue` tapé sur l'autre serveur marche, la bienvenue est relayée par le chat du réseau.
- **Accueils** : un joueur n'accueille qu'une fois chaque nouveau joueur (`INSERT IGNORE` dans `welcome_welcomes`) et la limite `daily_limit` par jour se compte en base (`welcome_daily`), quel que soit le serveur où il clique. La réservation, le décompte et la décision de récompenser se font dans une seule transaction.
- **Base indisponible** : l'accueil est refusé avec un message, rien n'est compté.

Tables dans la base du réseau, créées au démarrage si l'utilisateur a le droit CREATE (`sql/schema.sql` pour le faire à la main) : `welcome_announced`, `welcome_welcomes`, `welcome_daily`. Les lignes de plus de `reseau.conservation-jours` jours sont supprimées au démarrage.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/bienvenue [pseudo]` | `/b` | Accueillir un nouveau joueur (le dernier arrivé sans pseudo). Le clic sur l'annonce lance cette commande. | `shwelcome.use` |
| `/shwelcome reload` | — | Recharge `config.yml` et `messages.yml` (console comprise). Un fichier YAML invalide laisse l'ancienne configuration en place et affiche le fichier et la ligne de l'erreur. Les accueils en attente et les compteurs ne sont pas touchés ; la section `reseau` demande un redémarrage. | `shwelcome.admin.reload` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shwelcome.use` | Souhaiter la bienvenue (`/bienvenue`, alias `/b`). | Tous |
| `shwelcome.admin` | Toutes les sous-commandes de `/shwelcome`. | `op` |
| `shwelcome.admin.reload` | `/shwelcome reload`. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHWelcome/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>

sql/
└── <a href="sql/schema.sql">schema.sql</a>
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
