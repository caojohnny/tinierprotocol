package io.github.caojohnny.tinierprotocol;

import org.bukkit.plugin.java.JavaPlugin;

public class TinierProtocolTest extends JavaPlugin {
    private final TinierProtocol protocol = new TinierProtocol(this);

    @Override
    public void onEnable() {
        this.protocol.setInHandler((clientConnection, o) -> {
            // System.out.println("IN: " + o.getClass().getSimpleName());
            return o;
        });
        this.protocol.setOutHandler((clientConnection, o) -> {
            // System.out.println("OUT: " + o.getClass().getSimpleName());
            return o;
        });

        this.protocol.begin();
    }

    @Override
    public void onDisable() {
        this.protocol.close();
    }
}
