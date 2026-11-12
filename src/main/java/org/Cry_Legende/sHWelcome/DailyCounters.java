package org.Cry_Legende.sHWelcome;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bienvenues récompensées par joueur depuis minuit (heure du serveur), gardées dans un fichier : un redémarrage ne
 * remet rien à zéro, seul le changement de jour le fait. Le fichier ne contient que le jour en cours.
 *
 * <p>Utilisé sans SHNetwork ; avec SHNetwork, le décompte est en base (voir {@code WelcomeDatabase}).</p>
 */
final class DailyCounters {

    private final File file;
    private final Clock clock;
    private final Map<UUID, Integer> counts = new HashMap<>();
    private LocalDate day;

    DailyCounters(File file, Clock clock) {
        this.file = file;
        this.clock = clock;
        this.day = LocalDate.now(clock);
    }

    /** Bienvenues récompensées par ce joueur aujourd'hui. */
    synchronized int count(UUID player) {
        rollOver();
        return counts.getOrDefault(player, 0);
    }

    /** Compte une bienvenue récompensée de plus aujourd'hui pour ce joueur. */
    synchronized void increment(UUID player) {
        rollOver();
        counts.merge(player, 1, Integer::sum);
    }

    /** Nouveau jour : les compteurs de la veille ne servent plus. */
    private void rollOver() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            counts.clear();
            day = today;
        }
    }

    /**
     * Relit le fichier. Un fichier d'un autre jour, absent ou illisible laisse les compteurs vides ; le message
     * renvoyé (nul si tout va bien) dit pourquoi un fichier présent n'a pas été repris.
     */
    synchronized String load() {
        counts.clear();
        day = LocalDate.now(clock);
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            return file.getName() + " illisible, compteurs du jour remis à zéro : " + e.getMessage();
        }
        LocalDate saved;
        try {
            saved = LocalDate.parse(String.valueOf(yaml.getString("jour")));
        } catch (DateTimeParseException e) {
            return file.getName() + " : jour illisible, compteurs du jour remis à zéro.";
        }
        if (!saved.equals(day)) {
            return null; // fichier de la veille ou plus vieux : minuit est passé
        }
        ConfigurationSection section = yaml.getConfigurationSection("accueils");
        if (section == null) {
            return null;
        }
        for (String key : section.getKeys(false)) {
            try {
                int value = section.getInt(key);
                if (value > 0) {
                    counts.put(UUID.fromString(key), value);
                }
            } catch (IllegalArgumentException e) {
                // ligne qui n'est pas un uuid : ignorée
            }
        }
        return null;
    }

    /** Écrit les compteurs du jour : fichier temporaire puis renommage, un arrêt brutal ne laisse jamais un fichier coupé. */
    synchronized void save() throws IOException {
        rollOver();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().header("Bienvenues récompensées par joueur depuis minuit. Géré par SHWelcome, ne pas modifier à la main.");
        yaml.set("jour", day.toString());
        for (Map.Entry<UUID, Integer> entry : counts.entrySet()) {
            yaml.set("accueils." + entry.getKey(), entry.getValue());
        }
        Path target = file.toPath();
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
