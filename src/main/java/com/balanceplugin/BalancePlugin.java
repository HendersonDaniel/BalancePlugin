package com.balanceplugin;

import org.bukkit.plugin.java.JavaPlugin;

public class BalancePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new BalanceListener(this), this);
        getLogger().info("BalancePlugin enabled — mesa gold, mending, elytras, and blocked spawners active.");
    }
}
