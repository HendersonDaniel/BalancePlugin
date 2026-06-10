package com.balanceplugin;

import com.github.retrooper.packetevents.PacketEvents;
import org.bukkit.plugin.java.JavaPlugin;

public class BalancePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new BalanceListener(this), this);
        getLogger().info("BalancePlugin enabled");
        PacketEvents.getAPI().getEventManager().registerListener(new EnchantPacketListener());
    }
    @Override
    public void onDisable() {
        PacketEvents.getAPI().getEventManager().unregisterAllListeners();
    }
}
