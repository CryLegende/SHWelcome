-- Tables de SHWelcome avec SHNetwork. Le plugin les crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à les créer à la main et à ne donner à l'utilisateur que les droits nécessaires.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

CREATE TABLE IF NOT EXISTS bo_net.welcome_announced (
    uuid          CHAR(36)    NOT NULL,          -- uuid « hors ligne » du nouveau joueur, le même sur tous les serveurs
    name          VARCHAR(32) NOT NULL,
    announced_at  BIGINT      NOT NULL,          -- heure de l'annonce (ms), la ligne est créée par INSERT IGNORE
    PRIMARY KEY (uuid),
    KEY idx_name (name, announced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS bo_net.welcome_welcomes (
    new_uuid       CHAR(36) NOT NULL,
    welcomer_uuid  CHAR(36) NOT NULL,
    welcomed_at    BIGINT   NOT NULL,            -- créée par INSERT IGNORE : un joueur n'accueille qu'une fois chaque nouveau
    PRIMARY KEY (new_uuid, welcomer_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS bo_net.welcome_daily (
    uuid          CHAR(36) NOT NULL,
    window_start  BIGINT   NOT NULL,             -- minuit du jour compté (ms), remis à zéro quand il est dépassé
    done          INT      NOT NULL DEFAULT 0,   -- accueils récompensés ce jour, augmentés par « done = done + 1 »
    PRIMARY KEY (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shwelcome'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.welcome_announced TO 'shwelcome'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.welcome_welcomes TO 'shwelcome'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.welcome_daily TO 'shwelcome'@'127.0.0.1';
-- Registre des premières connexions tenu par SHPlayerTrack : lecture seule
GRANT SELECT ON Global.sh_track_firstseen TO 'shwelcome'@'127.0.0.1';
