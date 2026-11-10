package org.Cry_Legende.sHWelcome.network;

import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;

import java.util.function.Consumer;

/** Pont vers SHNetwork. Cette classe n'est chargée que si SHNetwork est installé. */
public final class ShNetworkBus implements NetBus {

    private final SHNetworkAPI api;

    private ShNetworkBus() {
        this.api = SHNetworkAPI.get();
    }

    /** Point d'entrée : le plugin ne résout cette classe qu'à cet appel, donc jamais sans SHNetwork. */
    public static NetBus open() {
        return new ShNetworkBus();
    }

    @Override
    public void publish(String channel, String json) {
        api.publish(channel, json, true);
    }

    @Override
    public void listen(String channel, Consumer<String> handler) {
        api.subscribe(channel, message -> handler.accept(message.json()), true);
    }

    @Override
    public boolean isOnline(String name) {
        return api.find(name).isPresent();
    }
}
