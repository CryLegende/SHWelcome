<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHWelcome

*/b : souhaiter la bienvenue aux nouveaux joueurs contre une petite récompense.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

/b : souhaiter la bienvenue aux nouveaux joueurs contre une petite récompense.

Utilisé sur : **Opale**.

## Dépendances

- Optionnelle : `SHLogs`

## Logs

Chaque bienvenue récompensée est écrite dans SHLogs, dans le fichier `bienvenues` (`log_file`). Le texte de la ligne se règle avec `reward_log_format` et la récompense affichée avec `reward_log_text`. Une bienvenue sans récompense (limite quotidienne atteinte) n'est pas écrite. Sans SHLogs, la ligne est écrite dans la console.

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/b` | — | Accueillir un nouveau joueur | — |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHWelcome/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
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
