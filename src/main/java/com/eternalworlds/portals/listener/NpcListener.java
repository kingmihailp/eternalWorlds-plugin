package com.eternalworlds.portals.listener;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.model.NpcData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public class NpcListener implements Listener {

    private final EternalWorldsPlugin plugin;

    public NpcListener(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Right-click on NPC → dispatch click command from the player. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        int    entityId = event.getRightClicked().getEntityId();
        String npcId    = plugin.getNpcManager().getNpcIdByEntityId(entityId);
        if (npcId == null) return;

        event.setCancelled(true);

        NpcData data = plugin.getNpcManager().getNpc(npcId);
        if (data == null || data.getClickCommand() == null) return;

        String cmd = data.getClickCommand()
                .replace("{player}", event.getPlayer().getName())
                .replace("{npc}",    npcId);
        plugin.getServer().dispatchCommand(event.getPlayer(), cmd);
    }

    /** Prevent any damage to NPC entities. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent event) {
        if (plugin.getNpcManager().getNpcIdByEntityId(event.getEntity().getEntityId()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * When a real player joins: send NPC skin (PlayerInfo) packets to them.
     * When an NPC "joins" (placeNewPlayer fires this): suppress the join message
     * and remove it from the tab list after a short delay.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getNpcManager().isNpcPlayer(event.getPlayer())) {
            // This is a fake NPC player — suppress join message and remove from tab list
            event.joinMessage(null);
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> plugin.getNpcManager().removeNpcFromTabList(event.getPlayer().getUniqueId()),
                    40L);
        } else {
            // Real player — send NPC skin info
            plugin.getNpcManager().onRealPlayerJoin(event.getPlayer());
        }
    }

    /** Suppress the quit message when an NPC is despawned. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (plugin.getNpcManager().isNpcPlayer(event.getPlayer())) {
            event.quitMessage(null);
        }
    }
}
