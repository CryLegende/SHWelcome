package org.Cry_Legende.sHWelcome.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accueils en MariaDB, partagés par tous les serveurs : qui a été annoncé comme nouveau joueur, qui l'a déjà
 * accueilli, combien d'accueils chacun a faits depuis minuit. Chargée seulement avec SHNetwork
 * (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal et hors du fil du bus.</p>
 */
public final class WelcomeDatabase {

    static final String ANNOUNCED = "welcome_announced";
    static final String WELCOMES = "welcome_welcomes";
    static final String DAILY = "welcome_daily";

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?");

    /** Nouveau joueur annoncé : son uuid, son pseudo et l'heure de l'annonce (ms). */
    public record Announced(UUID uuid, String name, long at) {
    }

    private final HikariDataSource pool;
    private final String firstSeenTable;

    /**
     * @param firstSeenTable table de la première connexion de chaque compte (colonnes name en minuscules et first_played),
     *                       éventuellement préfixée par sa base
     */
    public WelcomeDatabase(String url, String user, String password, int timeoutMs, String firstSeenTable) {
        this(open(url, user, password, timeoutMs), firstSeenTable);
    }

    WelcomeDatabase(HikariDataSource pool, String firstSeenTable) {
        if (!TABLE_NAME.matcher(firstSeenTable).matches()) {
            throw new IllegalArgumentException("Nom de table invalide : " + firstSeenTable);
        }
        this.pool = pool;
        this.firstSeenTable = firstSeenTable;
    }

    private static HikariDataSource open(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHWelcome");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(3);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : les accueils sont alors refusés avec un message
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        return new HikariDataSource(hikari);
    }

    /** Crée les tables si elles manquent. Des tables déjà là ne demandent aucun droit CREATE à l'utilisateur. */
    public void createTables() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            String options = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + ANNOUNCED + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "name VARCHAR(32) NOT NULL, "
                + "announced_at BIGINT NOT NULL, "
                + "PRIMARY KEY (uuid), "
                + "KEY idx_name (name, announced_at)" + options);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + WELCOMES + " ("
                + "new_uuid CHAR(36) NOT NULL, "
                + "welcomer_uuid CHAR(36) NOT NULL, "
                + "welcomed_at BIGINT NOT NULL, "
                + "PRIMARY KEY (new_uuid, welcomer_uuid)" + options);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + DAILY + " ("
                + "uuid CHAR(36) NOT NULL, "
                + "window_start BIGINT NOT NULL, "
                + "done INT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (uuid)" + options);
        }
    }

    /**
     * Vrai si la première connexion du compte sur le réseau date de moins de {@code maxAgeMs}, ou si le registre ne
     * le connaît pas encore (un compte qu'aucun serveur n'a jamais vu est nouveau). Un vieux compte qui arrive sur
     * un serveur où il n'avait jamais joué n'est pas un nouveau joueur.
     */
    public boolean isRecentFirstConnection(String name, long maxAgeMs, long now) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT first_played FROM " + firstSeenTable + " WHERE name = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return true;
                }
                Timestamp first = rs.getTimestamp(1);
                return first == null || now - first.getTime() <= maxAgeMs;
            }
        }
    }

    /** Réserve l'annonce de ce nouveau joueur : vrai pour le seul serveur qui l'obtient, faux s'il a déjà été annoncé. */
    public boolean claimAnnounce(UUID uuid, String name, long now) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + ANNOUNCED + " (uuid, name, announced_at) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, now);
            return ps.executeUpdate() == 1;
        }
    }

    /** Dernier nouveau joueur annoncé sous ce pseudo depuis {@code since} (ms), pour un serveur qui n'a pas reçu l'annonce. */
    public Optional<Announced> findAnnounced(String name, long since) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, name, announced_at FROM " + ANNOUNCED
                 + " WHERE name = ? AND announced_at >= ? ORDER BY announced_at DESC LIMIT 1")) {
            ps.setString(1, name);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                    ? Optional.of(new Announced(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)))
                    : Optional.empty();
            }
        }
    }

    /** Résultat d'un accueil : déjà fait par ce joueur, limite du jour atteinte, ou accueil récompensé. */
    public enum Outcome {
        ALREADY_WELCOMED, DAILY_LIMIT, REWARDED
    }

    /**
     * Enregistre l'accueil de ce nouveau joueur par ce joueur et compte un accueil de plus dans sa journée,
     * dans une seule transaction. La réservation est un {@code INSERT IGNORE} : le serveur qui l'obtient est le seul à
     * continuer, l'autre répond {@link Outcome#ALREADY_WELCOMED}. Le décompte du jour est un {@code UPDATE ... WHERE done < ?}
     * dont le nombre de lignes touchées décide seul : deux serveurs qui comptent le même joueur ne dépassent jamais la
     * limite. Le compteur repart de zéro à minuit : une ligne dont le jour ({@code window_start}) est antérieur à
     * {@code dayStart} est remise à zéro. Une limite atteinte garde l'accueil marqué comme fait (la bienvenue est dite,
     * sans récompense). En cas d'erreur, rien n'est enregistré.
     *
     * @param dayStart minuit du jour en cours (ms), heure du serveur
     */
    public Outcome welcome(UUID newPlayer, UUID welcomer, int limit, long dayStart, long now) throws SQLException {
        try (Connection c = pool.getConnection()) {
            // La ligne du compteur est créée hors transaction : dans une transaction, deux serveurs qui la créent
            // en même temps s'interbloquent (verrou de clé en double).
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + DAILY + " (uuid, window_start, done) VALUES (?, ?, 0)")) {
                ps.setString(1, welcomer.toString());
                ps.setLong(2, dayStart);
                ps.executeUpdate();
            }
            for (int attempt = 1; ; attempt++) {
                try {
                    return welcomeTransaction(c, newPlayer, welcomer, limit, dayStart, now);
                } catch (SQLTransactionRollbackException e) {
                    if (attempt >= 3) {
                        throw e; // interblocage répété : l'accueil n'est pas compté, le joueur peut réessayer
                    }
                }
            }
        }
    }

    private Outcome welcomeTransaction(Connection c, UUID newPlayer, UUID welcomer, int limit, long dayStart, long now) throws SQLException {
        c.setAutoCommit(false);
        try {
            boolean first;
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + WELCOMES + " (new_uuid, welcomer_uuid, welcomed_at) VALUES (?, ?, ?)")) {
                ps.setString(1, newPlayer.toString());
                ps.setString(2, welcomer.toString());
                ps.setLong(3, now);
                first = ps.executeUpdate() == 1;
            }
            Outcome outcome = Outcome.ALREADY_WELCOMED;
            if (first) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + DAILY + " SET window_start = ?, done = 0 WHERE uuid = ? AND window_start < ?")) {
                    ps.setLong(1, dayStart);
                    ps.setString(2, welcomer.toString());
                    ps.setLong(3, dayStart);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE " + DAILY + " SET done = done + 1 WHERE uuid = ? AND done < ?")) {
                    ps.setString(1, welcomer.toString());
                    ps.setInt(2, limit);
                    outcome = ps.executeUpdate() == 1 ? Outcome.REWARDED : Outcome.DAILY_LIMIT;
                }
            }
            c.commit();
            return outcome;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** Supprime les lignes plus vieilles que {@code before} (ms) : elles ne servent plus une fois la fenêtre d'accueil passée. */
    public void purge(long before) throws SQLException {
        try (Connection c = pool.getConnection()) {
            for (String sql : new String[]{
                "DELETE FROM " + ANNOUNCED + " WHERE announced_at < ?",
                "DELETE FROM " + WELCOMES + " WHERE welcomed_at < ?",
                "DELETE FROM " + DAILY + " WHERE window_start < ?"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, before);
                    ps.executeUpdate();
                }
            }
        }
    }

    public void close() {
        pool.close();
    }
}
