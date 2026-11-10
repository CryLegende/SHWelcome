package org.Cry_Legende.sHWelcome;

import net.md_5.bungee.api.chat.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.Cry_Legende.sHWelcome.network.NetBus;
import org.Cry_Legende.sHWelcome.network.ShNetworkBus;
import org.Cry_Legende.sHWelcome.network.WelcomeDatabase;
import org.Cry_Legende.sHWelcome.utils.ActionLog;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SHWelcome extends JavaPlugin implements Listener {

    private final Map<String, NewPlayerEntry> newPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerWelcomeData> welcomeData = new ConcurrentHashMap<>();
    private FileConfiguration messages;
    // Non nul seulement avec SHNetwork : nouveau joueur décidé pour tout le réseau, accueils comptés en base
    private NetworkWelcome net;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMessages();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::cleanupExpiredPlayers, 1200L, 1200L);
        enableNetwork();
    }

    @Override
    public void onDisable() {
        if (net != null) {
            net.close();
        }
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai. Les classes qui touchent à SHNetwork
     * ne sont résolues qu'ici : sans lui, rien n'est chargé et le plugin garde son fonctionnement d'origine.
     */
    private void enableNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        WelcomeDatabase db = null;
        try {
            NetBus bus = ShNetworkBus.open();
            db = new WelcomeDatabase(
                getConfig().getString("reseau.base-de-donnees.url"),
                getConfig().getString("reseau.base-de-donnees.utilisateur"),
                getConfig().getString("reseau.base-de-donnees.mot-de-passe"),
                Math.max(250, getConfig().getInt("reseau.base-de-donnees.delai-ms", 3000)),
                getConfig().getString("reseau.premiere-connexion.table", "Global.sh_track_firstseen"));
            NetworkWelcome welcome = new NetworkWelcome(this, bus, db, getConfig().getString("reseau.canal", "welcome"));
            net = welcome;
            welcome.enable();
            getLogger().info("SHNetwork détecté : nouveaux joueurs et accueils partagés entre les serveurs, en base.");
        } catch (RuntimeException | LinkageError e) {
            net = null;
            if (db != null) db.close();
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, accueils locaux à ce serveur : " + e);
        }
    }

    private void loadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) {
            saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = getResource("messages.yml")) {
            if (in != null) {
                messages.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            getLogger().warning("messages.yml par défaut illisible : " + e.getMessage());
        }
    }

    String message(String key) {
        String value = messages.getString(key);
        return value == null ? "" : value;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) return;
        UUID uuid = player.getUniqueId();
        String name = player.getName();

        Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
            if (net != null) {
                // Joueur jamais vu sur ce serveur : le réseau décide s'il est vraiment nouveau
                net.newcomerJoined(uuid, name);
                return;
            }
            announce(name, uuid, System.currentTimeMillis());
        }, 10L);
    }

    /**
     * Annonce un nouveau joueur aux joueurs de ce serveur et le garde en mémoire le temps qu'on puisse l'accueillir.
     * Avec SHNetwork, l'annonce arrive du bus sur chaque serveur.
     */
    void announce(String newName, UUID uuid, long joinTime) {
        String rawMessage = sanitizeMessage(message("clickable_message"))
            .replace("%player%", newName);

        TextComponent component = new TextComponent(TextComponent.fromLegacyText(
            ChatColor.translateAlternateColorCodes('&', rawMessage)
        ));

        component.setClickEvent(new ClickEvent(
            ClickEvent.Action.RUN_COMMAND,
            "/b \"" + newName + "\""
        ));

        component.setHoverEvent(new HoverEvent(
            HoverEvent.Action.SHOW_TEXT,
            new ComponentBuilder().append(TextComponent.fromLegacyText(color(message("clickable_hover").replace("%player%", newName)))).create()
        ));

        Bukkit.getOnlinePlayers().stream()
            .filter(p -> !p.getName().equalsIgnoreCase(newName))
            .forEach(p -> p.spigot().sendMessage(component));

        newPlayers.put(newName.toLowerCase(), new NewPlayerEntry(newName, uuid, joinTime));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("b") && sender instanceof Player) {
            handleWelcomeCommand((Player) sender, args);
            return true;
        }
        return false;
    }

    private void handleWelcomeCommand(Player player, String[] args) {
        String targetName = args.length > 0 ? String.join(" ", args) : null;
        if (net != null) {
            net.welcome(player, targetName);
            return;
        }
        NewPlayerEntry entry = null;

        if (targetName != null) {
            // Nettoyage des guillemets et caractères spéciaux
            targetName = cleanName(targetName);
            entry = newPlayers.get(targetName.toLowerCase());
        } else {
            entry = latestEntryFor(player.getUniqueId());
        }

        if (entry == null) {
            player.sendMessage(color(message("no_new_players")));
            return;
        }

        if (isExpired(entry)) {
            player.sendMessage(color(message("cooldown_expired")));
            newPlayers.remove(entry.playerName.toLowerCase());
            return;
        }

        if (entry.welcomers.contains(player.getUniqueId())) {
            player.sendMessage(color(message("already_welcomed")));
            return;
        }

        PlayerWelcomeData data = welcomeData.computeIfAbsent(player.getUniqueId(), k -> new PlayerWelcomeData());
        checkDailyReset(data);

        // Vérification de la limite journalière
        if (data.count >= getConfig().getInt("daily_limit")) {
            player.sendMessage(color(message("daily_limit_reached")
                .replace("%limit%", String.valueOf(getConfig().getInt("daily_limit")))));
            // Même si la récompense n'est pas donnée, on marque que le joueur a effectué son accueil
            entry.welcomers.add(player.getUniqueId());

            // Envoi du message de bienvenue
            Player newPlayer = Bukkit.getPlayer(entry.playerName);
            if (newPlayer != null && newPlayer.isOnline()) {
                greet(player, newPlayer.getName());
            }
            return;
        }

        // Si la limite n'est pas atteinte, on poursuit normalement
        entry.welcomers.add(player.getUniqueId());
        data.count++;

        Player newPlayer = Bukkit.getPlayer(entry.playerName);
        if (newPlayer != null && newPlayer.isOnline()) {
            greet(player, newPlayer.getName());
        }

        reward(player, entry.playerName);
    }

    /** Pseudo tapé après /b, sans guillemets ni barres obliques inverses. */
    String cleanName(String typed) {
        return typed.replace("\"", "").replace("\\", "");
    }

    /** Nouveau joueur encore accueillable que ce joueur n'a pas accueilli, le dernier arrivé. */
    NewPlayerEntry latestEntryFor(UUID welcomer) {
        return newPlayers.values().stream()
            .filter(e -> !e.welcomers.contains(welcomer))
            .max(Comparator.comparingLong(e -> e.joinTime))
            .orElse(null);
    }

    NewPlayerEntry entryNamed(String name) {
        return newPlayers.get(name.toLowerCase());
    }

    /** Garde en mémoire un nouveau joueur connu par la base mais dont l'annonce n'est pas arrivée sur ce serveur. */
    NewPlayerEntry remember(String name, UUID uuid, long joinTime) {
        return newPlayers.computeIfAbsent(name.toLowerCase(), k -> new NewPlayerEntry(name, uuid, joinTime));
    }

    void forget(NewPlayerEntry entry) {
        newPlayers.remove(entry.playerName.toLowerCase());
    }

    /** Vrai quand le délai pour accueillir ce joueur est dépassé. */
    boolean isExpired(NewPlayerEntry entry) {
        return System.currentTimeMillis() - entry.joinTime > windowMillis();
    }

    long windowMillis() {
        return getConfig().getInt("cooldown_seconds") * 1000L;
    }

    /** Le joueur écrit dans le chat une ligne de bienvenue au nouveau joueur. */
    void greet(Player welcomer, String newPlayerName) {
        List<String> messages = getConfig().getStringList("messages_de_bienvenue");
        String msg = messages.get(new Random().nextInt(messages.size()))
            .replace("%new_player%", sanitizeMessage(newPlayerName));
        // Envoi sécurisé du message
        welcomer.chat(sanitizeMessage(msg));
    }

    /** Commandes de récompense, puis ligne dans SHLogs. */
    void reward(Player welcomer, String newPlayerName) {
        getConfig().getStringList("reward_commands").forEach(command ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                command.replace("%player%", welcomer.getName()))
        );

        ActionLog.write(this, getConfig().getString("log_file", "bienvenues"),
            getConfig().getString("reward_log_format", "%player% -> souhaite la bienvenue à %new_player% (%reward%)")
                .replace("%player%", welcomer.getName())
                .replace("%new_player%", newPlayerName)
                .replace("%reward%", getConfig().getString("reward_log_text", "2 tokens et 5 EXP de network")));
    }

    private String sanitizeMessage(String message) {
        return ChatColor.stripColor(message);
    }

    private void checkDailyReset(PlayerWelcomeData data) {
        LocalDateTime lastReset = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(data.lastReset),
            ZoneId.systemDefault()
        );
        if (lastReset.plusDays(1).isBefore(LocalDateTime.now())) {
            data.count = 0;
            data.lastReset = System.currentTimeMillis();
        }
    }

    private void cleanupExpiredPlayers() {
        long currentTime = System.currentTimeMillis();
        newPlayers.entrySet().removeIf(entry ->
            currentTime - entry.getValue().joinTime > getConfig().getInt("cooldown_seconds") * 1000L
        );
    }

    String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static class PlayerWelcomeData {
        int count = 0;
        long lastReset = System.currentTimeMillis();
    }
}
