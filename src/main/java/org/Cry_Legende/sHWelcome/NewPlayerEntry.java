package org.Cry_Legende.sHWelcome;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Nouveau joueur qu'on peut encore accueillir. Lu et modifié sur le fil principal seulement.
 */
final class NewPlayerEntry {

    final long joinTime;
    final Set<UUID> welcomers = new HashSet<>();
    final String playerName;
    final UUID uuid;

    NewPlayerEntry(String playerName, UUID uuid, long joinTime) {
        this.playerName = playerName;
        this.uuid = uuid;
        this.joinTime = joinTime;
    }
}
