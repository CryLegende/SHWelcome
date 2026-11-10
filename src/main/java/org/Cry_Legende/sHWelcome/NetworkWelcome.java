package org.Cry_Legende.sHWelcome;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.Cry_Legende.sHWelcome.network.NetBus;
import org.Cry_Legende.sHWelcome.network.WelcomeDatabase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Accueil des nouveaux joueurs partagé entre les serveurs Opale. Un joueur est nouveau pour tout le réseau (sa première
 * connexion récente dans le registre des premières connexions, et pas déjà annoncé par un autre serveur), son annonce
 * part sur tous les serveurs, et chaque accueil est compté en base : un joueur n'accueille qu'une fois chaque nouveau
 * joueur, et reste limité par jour quel que soit le serveur où il clique. Chargée seulement avec SHNetwork.
 *
 * <p>Aucune méthode ne touche la base sur le fil principal ni sur le fil du bus.</p>
 */
final class NetworkWelcome {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    private final SHWelcome plugin;
    private final NetBus bus;
    private final WelcomeDatabase db;
    private final String channel;

    NetworkWelcome(SHWelcome plugin, NetBus bus, WelcomeDatabase db, String channel) {
        this.plugin = plugin;
        this.bus = bus;
        this.db = db;
        this.channel = channel;
    }

    /** Crée les tables, purge les anciennes lignes et se met à l'écoute des annonces des autres serveurs. */
    void enable() {
        bus.listen(channel, this::onAnnounce);
        long keep = Math.max(1, plugin.getConfig().getInt("reseau.conservation-jours", 30)) * DAY_MILLIS;
        async(() -> {
            try {
                db.createTables();
                db.purge(System.currentTimeMillis() - keep);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Tables des accueils non vérifiées (base injoignable ou droits manquants) : " + e.getMessage());
            }
        });
    }

    void close() {
        db.close();
    }

    // ------------------------------------------------------------------ arrivée d'un joueur jamais vu ici

    /** Joueur arrivé pour la première fois sur ce serveur (fil principal) : annoncé s'il est nouveau pour tout le réseau. */
    void newcomerJoined(UUID uuid, String name) {
        long maxAge = Math.max(1, plugin.getConfig().getInt("reseau.premiere-connexion.duree-max-minutes", 60)) * 60_000L;
        async(() -> {
            long now = System.currentTimeMillis();
            try {
                if (!db.isRecentFirstConnection(name, maxAge, now)) {
                    return; // vieux compte qui découvre ce serveur : pas un nouveau joueur
                }
                if (!db.claimAnnounce(uuid, name, now)) {
                    return; // déjà annoncé par l'autre serveur
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Nouveau joueur " + name + " non annoncé, base des accueils injoignable : " + e.getMessage());
                return;
            }
            JsonObject json = new JsonObject();
            json.addProperty("uuid", uuid.toString());
            json.addProperty("name", name);
            json.addProperty("at", now);
            bus.publish(channel, json.toString());
        });
    }

    /** Annonce reçue du bus (fil principal), y compris celle de ce serveur. */
    private void onAnnounce(String message) {
        UUID uuid;
        String name;
        long at;
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            uuid = UUID.fromString(json.get("uuid").getAsString());
            name = json.get("name").getAsString();
            at = json.get("at").getAsLong();
        } catch (RuntimeException e) {
            return; // message illisible : le bus ne s'arrête jamais pour lui
        }
        if (System.currentTimeMillis() - at > plugin.windowMillis()) {
            return; // arrivé trop tard pour qu'on puisse encore l'accueillir
        }
        plugin.announce(name, uuid, at);
    }

    // ------------------------------------------------------------------ /b

    /** /b exécuté sur ce serveur (fil principal) ; {@code typedName} est nul quand le joueur n'a pas donné de pseudo. */
    void welcome(Player player, String typedName) {
        String target = typedName == null ? null : plugin.cleanName(typedName);
        NewPlayerEntry entry = target != null ? plugin.entryNamed(target) : plugin.latestEntryFor(player.getUniqueId());
        if (entry != null) {
            proceed(player, entry);
            return;
        }
        if (target == null) {
            player.sendMessage(plugin.color(plugin.message("no_new_players")));
            return;
        }
        // L'annonce n'est pas arrivée sur ce serveur (message du bus perdu) : la base la connaît peut-être
        long since = System.currentTimeMillis() - plugin.windowMillis();
        async(() -> {
            Optional<WelcomeDatabase.Announced> found;
            try {
                found = db.findAnnounced(target, since);
            } catch (SQLException e) {
                failed(player, null, e);
                return;
            }
            sync(() -> {
                if (found.isEmpty()) {
                    player.sendMessage(plugin.color(plugin.message("no_new_players")));
                    return;
                }
                WelcomeDatabase.Announced announced = found.get();
                proceed(player, plugin.remember(announced.name(), announced.uuid(), announced.at()));
            });
        });
    }

    /** Délai, accueil déjà fait, limite du jour : la base tranche, le serveur qui gagne la réservation récompense. */
    private void proceed(Player player, NewPlayerEntry entry) {
        if (plugin.isExpired(entry)) {
            player.sendMessage(plugin.color(plugin.message("cooldown_expired")));
            plugin.forget(entry);
            return;
        }
        UUID welcomer = player.getUniqueId();
        // Le marqueur est posé tout de suite : un double clic ne lance pas deux demandes
        if (!entry.welcomers.add(welcomer)) {
            player.sendMessage(plugin.color(plugin.message("already_welcomed")));
            return;
        }
        int limit = plugin.getConfig().getInt("daily_limit");
        async(() -> {
            WelcomeDatabase.Outcome outcome;
            try {
                outcome = db.welcome(entry.uuid, welcomer, limit, DAY_MILLIS, System.currentTimeMillis());
            } catch (SQLException e) {
                failed(player, entry, e);
                return;
            }
            boolean newcomerOnline = bus.isOnline(entry.playerName);
            sync(() -> {
                if (outcome == WelcomeDatabase.Outcome.ALREADY_WELCOMED) {
                    player.sendMessage(plugin.color(plugin.message("already_welcomed")));
                    return;
                }
                if (outcome == WelcomeDatabase.Outcome.DAILY_LIMIT) {
                    player.sendMessage(plugin.color(plugin.message("daily_limit_reached")
                        .replace("%limit%", String.valueOf(limit))));
                }
                // Le nouveau joueur peut être sur l'autre serveur : le chat du réseau relaie la ligne de bienvenue
                if (newcomerOnline && player.isOnline()) {
                    plugin.greet(player, entry.playerName);
                }
                if (outcome == WelcomeDatabase.Outcome.REWARDED) {
                    plugin.reward(player, entry.playerName);
                }
            });
        });
    }

    /** Base injoignable : le joueur peut réessayer, rien n'a été compté. */
    private void failed(Player player, NewPlayerEntry entry, SQLException e) {
        plugin.getLogger().log(Level.WARNING, "Accueil impossible, base des accueils injoignable : " + e.getMessage());
        sync(() -> {
            if (entry != null) {
                entry.welcomers.remove(player.getUniqueId());
            }
            player.sendMessage(plugin.color(plugin.message("service_unavailable")));
        });
    }

    private void async(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    private void sync(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
