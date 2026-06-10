package com.balanceplugin;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowProperty;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

public class EnchantPacketListener extends PacketListenerAbstract {

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.WINDOW_PROPERTY) {
            return;
        }

        Object playerObj = event.getPlayer();

        if (!(playerObj instanceof Player player)) {
            return;
        }

        if (player.getOpenInventory().getTopInventory().getType() != InventoryType.ENCHANTING) {
            return;
        }

        WrapperPlayServerWindowProperty packet = new WrapperPlayServerWindowProperty(event);

        int property = packet.getId();

        // 4, 5, and 6 are the enchantment type hints.
        if (property >= 4 && property <= 6) {
            packet.setValue(-1);
        }

    }
}

