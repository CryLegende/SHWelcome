package org.Cry_Legende.sHWelcome;

import net.md_5.bungee.api.chat.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SHWelcome extends JavaPlugin implements Listener {

    private final Map<String, NewPlayerEntry> newPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerWelcomeData> welcomeData = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::cleanupExpiredPlayers, 1200L, 1200L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) return;

        Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> {
            String rawMessage = sanitizeMessage(getConfig().getString("clickable_message"))
                .replace("%player%", player.getName());

            TextComponent component = new TextComponent(TextComponent.fromLegacyText(
                ChatColor.translateAlternateColorCodes('&', rawMessage)
            ));

            component.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/b \"" + player.getName() + "\"" // Ajout des guillemets
            ));

            component.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("Cliquez pour accueillir " + player.getName()).create()
            ));

            Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.getName().equalsIgnoreCase(player.getName()))
                .forEach(p -> p.spigot().sendMessage(component));

            newPlayers.put(player.getName().toLowerCase(), new NewPlayerEntry(player.getName()));
        }, 10L);
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
        NewPlayerEntry entry = null;

        if (targetName != null) {
            // Nettoyage des guillemets et caractères spéciaux
            targetName = targetName.replace("\"", "").replace("\\", "");
            entry = newPlayers.get(targetName.toLowerCase());
        } else {
            Optional<NewPlayerEntry> optional = newPlayers.values().stream()
                .filter(e -> !e.welcomers.contains(player.getUniqueId()))
                .max(Comparator.comparingLong(e -> e.joinTime));
            if (optional.isPresent()) entry = optional.get();
        }

        if (entry == null) {
            player.sendMessage(color(getConfig().getString("messages.no_new_players")));
            return;
        }

        if (System.currentTimeMillis() - entry.joinTime > getConfig().getInt("cooldown_seconds") * 1000L) {
            player.sendMessage(color(getConfig().getString("messages.cooldown_expired")));
            newPlayers.remove(entry.playerName.toLowerCase());
            return;
        }

        if (entry.welcomers.contains(player.getUniqueId())) {
            player.sendMessage(color(getConfig().getString("messages.already_welcomed")));
            return;
        }

        PlayerWelcomeData data = welcomeData.computeIfAbsent(player.getUniqueId(), k -> new PlayerWelcomeData());
        checkDailyReset(data);

        // Vérification de la limite journalière
        if (data.count >= getConfig().getInt("daily_limit")) {
            player.sendMessage(color(getConfig().getString("messages.daily_limit_reached")
                .replace("%limit%", String.valueOf(getConfig().getInt("daily_limit")))));
            // Même si la récompense n'est pas donnée, on marque que le joueur a effectué son accueil
            entry.welcomers.add(player.getUniqueId());

            // Envoi du message de bienvenue
            Player newPlayer = Bukkit.getPlayer(entry.playerName);
            if (newPlayer != null && newPlayer.isOnline()) {
                List<String> messages = getConfig().getStringList("messages_de_bienvenue");
                String msg = messages.get(new Random().nextInt(messages.size()))
                    .replace("%new_player%", sanitizeMessage(newPlayer.getName()));
                // Envoi sécurisé du message
                player.chat(sanitizeMessage(msg));
            }
            return;
        }

        // Si la limite n'est pas atteinte, on poursuit normalement
        entry.welcomers.add(player.getUniqueId());
        data.count++;

        Player newPlayer = Bukkit.getPlayer(entry.playerName);
        if (newPlayer != null && newPlayer.isOnline()) {
            List<String> messages = getConfig().getStringList("messages_de_bienvenue");
            String msg = messages.get(new Random().nextInt(messages.size()))
                .replace("%new_player%", sanitizeMessage(newPlayer.getName()));
            // Envoi sécurisé du message
            player.chat(sanitizeMessage(msg));
        }

        getConfig().getStringList("reward_commands").forEach(command ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                command.replace("%player%", player.getName()))
        );
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

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static class NewPlayerEntry {
        final long joinTime = System.currentTimeMillis();
        final Set<UUID> welcomers = new HashSet<>();
        final String playerName;

        NewPlayerEntry(String playerName) {
            this.playerName = playerName;
        }
    }

    private static class PlayerWelcomeData {
        int count = 0;
        long lastReset = System.currentTimeMillis();
    }
}
