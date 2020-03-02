package io.github.agenttroll.tinierprotocol;

import org.bukkit.plugin.java.JavaPlugin;

public class TinierProtocolTest extends JavaPlugin {
    private final TinierProtocol protocol = new TinierProtocol(this);

    @Override
    public void onEnable() {
        protocol.setInHandler((clientConnection, o) -> {
            // System.out.println("IN: " + o.getClass().getSimpleName());
            return o;
        });
        protocol.setOutHandler((clientConnection, o) -> {
            // System.out.println("OUT: " + o.getClass().getSimpleName());
            return o;
        });

        protocol.begin();
    }

    @Override
    public void onDisable() {
        this.protocol.close();
    }
}
