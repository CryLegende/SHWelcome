package org.Cry_Legende.sHWelcome.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.Cry_Legende.sHWelcome.network.WelcomeDatabase.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Réservations des accueils sur un vrai MariaDB : un nouveau joueur annoncé une seule fois, un accueil récompensé une
 * seule fois, la limite du jour jamais dépassée par deux serveurs. Ignoré sans SHWELCOME_TEST_URL (une base d'essai
 * dont le nom contient « test », par exemple jdbc:mariadb://127.0.0.1:3399/zz_shwelcome_test), SHWELCOME_TEST_USER
 * et SHWELCOME_TEST_PASSWORD.
 */
class WelcomeDatabaseTest {

    private static final String FIRST_SEEN = "zz_firstseen_test";
    private static final long DAY = 24L * 60 * 60 * 1000;

    private HikariDataSource pool;
    private WelcomeDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("SHWELCOME_TEST_URL");
        Assumptions.assumeTrue(url != null && url.contains("test"), "SHWELCOME_TEST_URL absent ou sans « test » : test MariaDB ignoré");
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(url);
        hikari.setUsername(System.getenv().getOrDefault("SHWELCOME_TEST_USER", "shwelcome"));
        hikari.setPassword(System.getenv().getOrDefault("SHWELCOME_TEST_PASSWORD", "shwelcome"));
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.setMaximumPoolSize(24);
        pool = new HikariDataSource(hikari);
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS " + WelcomeDatabase.ANNOUNCED);
            st.executeUpdate("DROP TABLE IF EXISTS " + WelcomeDatabase.WELCOMES);
            st.executeUpdate("DROP TABLE IF EXISTS " + WelcomeDatabase.DAILY);
            st.executeUpdate("DROP TABLE IF EXISTS " + FIRST_SEEN);
            st.executeUpdate("CREATE TABLE " + FIRST_SEEN + " (name VARCHAR(16) NOT NULL, display_name VARCHAR(16) NOT NULL, "
                + "first_played DATETIME NOT NULL, last_played DATETIME DEFAULT NULL, PRIMARY KEY (name)) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        }
        db = new WelcomeDatabase(pool, FIRST_SEEN);
        db.createTables();
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    private static void parallel(int threads, ThrowingRunnable task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                task.run();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Minuit du jour (UTC suffit pour les essais). */
    private static long midnight() {
        long now = System.currentTimeMillis();
        return now - now % DAY;
    }

    private void firstSeen(String name, long at) throws Exception {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO " + FIRST_SEEN + " (name, display_name, first_played) VALUES (?, ?, ?)")) {
            ps.setString(1, name.toLowerCase());
            ps.setString(2, name);
            ps.setTimestamp(3, new Timestamp(at));
            ps.executeUpdate();
        }
    }

    @Test
    void newPlayerIsAnnouncedByOneServerOnly() throws Exception {
        UUID player = UUID.randomUUID();
        List<Boolean> results = new CopyOnWriteArrayList<>();
        parallel(8, () -> results.add(db.claimAnnounce(player, "Nouveau", System.currentTimeMillis())));
        assertEquals(8, results.size());
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertFalse(db.claimAnnounce(player, "Nouveau", System.currentTimeMillis()));
    }

    @Test
    void announcementIsFoundByNameWhileTheWindowIsOpen() throws Exception {
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis();
        db.claimAnnounce(player, "Nouveau", now);
        assertEquals(player, db.findAnnounced("nouveau", now - 60_000).orElseThrow().uuid());
        assertTrue(db.findAnnounced("Nouveau", now + 1).isEmpty());
        assertTrue(db.findAnnounced("Autre", now - 60_000).isEmpty());
    }

    @Test
    void oldAccountIsNotNewButUnknownAndRecentAccountsAre() throws Exception {
        long now = System.currentTimeMillis();
        firstSeen("Ancien", now - 400 * DAY);
        firstSeen("Recent", now - 5 * 60_000);
        assertFalse(db.isRecentFirstConnection("Ancien", 60 * 60_000, now));
        assertTrue(db.isRecentFirstConnection("RECENT", 60 * 60_000, now));
        assertTrue(db.isRecentFirstConnection("Inconnu", 60 * 60_000, now));
    }

    @Test
    void welcomeIsRewardedOnceForTheSamePair() throws Exception {
        UUID newPlayer = UUID.randomUUID();
        UUID welcomer = UUID.randomUUID();
        List<Outcome> results = new CopyOnWriteArrayList<>();
        // deux serveurs reçoivent le même clic au même moment, plusieurs fois
        parallel(16, () -> results.add(db.welcome(newPlayer, welcomer, 5, midnight(), System.currentTimeMillis())));
        assertEquals(1, results.stream().filter(o -> o == Outcome.REWARDED).count());
        assertEquals(15, results.stream().filter(o -> o == Outcome.ALREADY_WELCOMED).count());
    }

    @Test
    void dailyLimitIsNeverExceededByTwoServers() throws Exception {
        UUID welcomer = UUID.randomUUID();
        List<Outcome> results = new CopyOnWriteArrayList<>();
        // 10 nouveaux joueurs différents accueillis en même temps, limite de 5 par jour
        parallel(10, () -> results.add(db.welcome(UUID.randomUUID(), welcomer, 5, midnight(), System.currentTimeMillis())));
        assertEquals(5, results.stream().filter(o -> o == Outcome.REWARDED).count());
        assertEquals(5, results.stream().filter(o -> o == Outcome.DAILY_LIMIT).count());
    }

    @Test
    void limitedWelcomeStaysMarkedAndCounterRestartsAtMidnight() throws Exception {
        UUID welcomer = UUID.randomUUID();
        long midnight = midnight();
        long start = midnight + 1000;
        assertEquals(Outcome.REWARDED, db.welcome(UUID.randomUUID(), welcomer, 1, midnight, start));
        UUID late = UUID.randomUUID();
        assertEquals(Outcome.DAILY_LIMIT, db.welcome(late, welcomer, 1, midnight, start + 1000));
        // l'accueil sans récompense reste fait : pas de seconde chance sur le même joueur
        assertEquals(Outcome.ALREADY_WELCOMED, db.welcome(late, welcomer, 1, midnight, start + 2000));
        // minuit passé, le compteur repart de zéro, même à moins de 24 heures du premier accueil
        long nextMidnight = midnight + DAY;
        assertEquals(Outcome.REWARDED, db.welcome(UUID.randomUUID(), welcomer, 1, nextMidnight, nextMidnight + 1000));
    }

    @Test
    void purgeRemovesOldRowsOnly() throws Exception {
        UUID oldPlayer = UUID.randomUUID();
        UUID recentPlayer = UUID.randomUUID();
        long now = System.currentTimeMillis();
        db.claimAnnounce(oldPlayer, "Vieux", now - 40 * DAY);
        db.claimAnnounce(recentPlayer, "Frais", now);
        db.purge(now - 30 * DAY);
        assertTrue(db.findAnnounced("Vieux", 0).isEmpty());
        assertTrue(db.findAnnounced("Frais", 0).isPresent());
    }
}
