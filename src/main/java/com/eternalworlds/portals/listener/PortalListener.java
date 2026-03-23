package com.eternalworlds.portals.listener;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.manager.SelectionManager;
import com.eternalworlds.portals.model.Portal;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PortalListener implements Listener {

    private final EternalWorldsPlugin plugin;
    /** Tracks when each player last used a portal (epoch ms). */
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public PortalListener(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- Wand interaction ----

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle main hand to avoid duplicate events
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!SelectionManager.isWand(event.getItem())) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("eternalworlds.portal.admin")) return;
        if (event.getClickedBlock() == null) return;

        Location loc = event.getClickedBlock().getLocation();
        Action action = event.getAction();

        if (action == Action.LEFT_CLICK_BLOCK) {
            plugin.getSelectionManager().setPos1(player, loc);
            event.setCancelled(true);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            plugin.getSelectionManager().setPos2(player, loc);
            event.setCancelled(true);
        }
    }

    // ---- Portal teleportation ----

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        // Skip if the player hasn't moved to a new block
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;

        Player player = event.getPlayer();

        // Check portal-use permission
        if (!plugin.getConfig().getBoolean("allow-player-use", true)
                && !player.hasPermission("eternalworlds.portal.admin")) return;
        if (!player.hasPermission("eternalworlds.portal.use")) return;

        Portal portal = plugin.getPortalManager().getPortalAt(
                to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (portal == null) return;

        // Cooldown check
        int cooldownSec = plugin.getConfig().getInt("portal-cooldown", 3);
        long now = System.currentTimeMillis();
        Long lastUsed = cooldowns.get(player.getUniqueId());
        if (lastUsed != null && now - lastUsed < cooldownSec * 1000L) return;
        cooldowns.put(player.getUniqueId(), now);

        // Ensure destination world is loaded
        World destWorld = plugin.getWorldManager().loadWorld(portal.getDestinationWorld());
        if (destWorld == null) {
            player.sendMessage("§c[Portals] Destination world '" + portal.getDestinationWorld()
                    + "' could not be loaded.");
            return;
        }

        Location destination = new Location(destWorld,
                portal.getDestX(), portal.getDestY(), portal.getDestZ(),
                portal.getDestYaw(), portal.getDestPitch());

        player.teleportAsync(destination).thenAccept(success -> {
            if (success && plugin.getConfig().getBoolean("teleport-message", true)) {
                String msg = plugin.getConfig()
                        .getString("teleport-message-text", "&aYou have been teleported to &b{world}&a!")
                        .replace("{world}", portal.getDestinationWorld())
                        .replace("&", "§");
                player.sendMessage(msg);
            }
        });
    }
}
