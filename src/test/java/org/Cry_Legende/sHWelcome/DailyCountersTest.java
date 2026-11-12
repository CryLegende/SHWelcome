package org.Cry_Legende.sHWelcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Compteurs de bienvenues : gardés au redémarrage, remis à zéro à minuit et pas avant. */
class DailyCountersTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    /** Horloge réglable : le test avance l'heure sans attendre. */
    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(String isoLocal) {
            this.now = java.time.LocalDateTime.parse(isoLocal).atZone(ZONE).toInstant();
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void countsSurviveARestartTheSameDay(@TempDir Path dir) throws Exception {
        File file = dir.resolve("compteurs.yml").toFile();
        TestClock clock = new TestClock("2026-11-12T10:00:00");
        UUID cry = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        DailyCounters before = new DailyCounters(file, clock);
        before.increment(cry);
        before.increment(cry);
        before.increment(other);
        before.save();

        clock.advance(Duration.ofHours(12)); // redémarrage à 22 h : rien n'est remis à zéro
        DailyCounters after = new DailyCounters(file, clock);
        assertNull(after.load());
        assertEquals(2, after.count(cry));
        assertEquals(1, after.count(other));
        assertEquals(0, after.count(UUID.randomUUID()));
    }

    @Test
    void countsResetAtMidnightWhileRunning() {
        TestClock clock = new TestClock("2026-11-12T23:59:00");
        DailyCounters counters = new DailyCounters(new File("inutilise.yml"), clock);
        UUID player = UUID.randomUUID();
        counters.increment(player);
        assertEquals(1, counters.count(player));

        clock.advance(Duration.ofMinutes(2)); // 00:01 le lendemain, moins de 24 h plus tard
        assertEquals(0, counters.count(player));
        counters.increment(player);
        assertEquals(1, counters.count(player));
    }

    @Test
    void fileOfYesterdayIsIgnoredAfterARestart(@TempDir Path dir) throws Exception {
        File file = dir.resolve("compteurs.yml").toFile();
        TestClock clock = new TestClock("2026-11-12T23:30:00");
        UUID player = UUID.randomUUID();
        DailyCounters before = new DailyCounters(file, clock);
        before.increment(player);
        before.save();

        clock.advance(Duration.ofHours(7)); // redémarrage à 06:30 le lendemain
        DailyCounters after = new DailyCounters(file, clock);
        assertNull(after.load());
        assertEquals(0, after.count(player));
    }

    @Test
    void unreadableFileStartsEmptyAndSaysWhy(@TempDir Path dir) throws Exception {
        File file = dir.resolve("compteurs.yml").toFile();
        Files.writeString(file.toPath(), "jour: [pas fermé\n");
        DailyCounters counters = new DailyCounters(file, new TestClock("2026-11-12T10:00:00"));
        assertNotNull(counters.load());
        assertEquals(0, counters.count(UUID.randomUUID()));
    }
}
