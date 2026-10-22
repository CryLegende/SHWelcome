package org.Cry_Legende.sHWelcome;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ServerLinks;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Liens du serveur (Discord, site, vote, boutique) affichés dans le menu pause du client : bouton
 * « Liens du serveur » de Minecraft 1.21+. Les clients plus anciens ignorent ce paquet.
 */
final class LiensServeur {

    private final SHWelcome plugin;
    private final List<ServerLinks.ServerLink> ajoutes = new ArrayList<>();

    LiensServeur(SHWelcome plugin) {
        this.plugin = plugin;
    }

    /** Ajoute les liens de la clé liens-serveur du config.yml. */
    void ajouter() {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("liens-serveur.actif", true)) {
            return;
        }

        ServerLinks liens = Bukkit.getServerLinks();
        for (Map<?, ?> entree : config.getMapList("liens-serveur.liens")) {
            if ("false".equalsIgnoreCase(String.valueOf(entree.get("actif")))) {
                continue;
            }
            Object libelle = entree.get("libelle");
            Object adresse = entree.get("url");
            if (libelle == null || adresse == null || adresse.toString().isBlank()) {
                plugin.getLogger().warning("Lien du serveur ignoré (libelle ou url manquant) : " + entree);
                continue;
            }

            URI uri;
            try {
                uri = new URI(adresse.toString().trim());
            } catch (URISyntaxException e) {
                plugin.getLogger().warning("Lien du serveur ignoré (url invalide) : " + adresse);
                continue;
            }
            // Le client n'ouvre que le http(s)
            String schema = uri.getScheme();
            if (schema == null || !(schema.equalsIgnoreCase("http") || schema.equalsIgnoreCase("https"))) {
                plugin.getLogger().warning("Lien du serveur ignoré (http ou https attendu) : " + adresse);
                continue;
            }

            ajoutes.add(liens.addLink(LegacyComponentSerializer.legacyAmpersand().deserialize(libelle.toString()), uri));
        }
        plugin.getLogger().info(ajoutes.size() + " lien(s) du serveur ajouté(s) au menu pause");
    }

    /** Retire les liens ajoutés, pour ne pas les doubler si le plugin est réactivé. */
    void retirer() {
        ServerLinks liens = Bukkit.getServerLinks();
        ajoutes.forEach(liens::removeLink);
        ajoutes.clear();
    }
}
