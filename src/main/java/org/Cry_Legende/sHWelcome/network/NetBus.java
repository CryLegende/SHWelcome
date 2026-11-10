package org.Cry_Legende.sHWelcome.network;

import java.util.function.Consumer;

/**
 * Ce dont SHWelcome a besoin de SHNetwork. Seule {@link ShNetworkBus} le connaît : sans lui,
 * aucune classe de ce paquet n'est chargée.
 */
public interface NetBus {

    /** Publie un message JSON sur le canal, reçu par tous les serveurs, celui-ci compris. */
    void publish(String channel, String json);

    /** Reçoit les messages du canal sur le fil principal. */
    void listen(String channel, Consumer<String> handler);

    /** Vrai si le joueur est connecté quelque part sur le réseau, ce serveur compris. */
    boolean isOnline(String name);
}
