package com.eternalworlds.portals.listener;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.manager.SelectionManager;
import com.eternalworlds.portals.manager.WorldConfigManager;
import com.eternalworlds.portals.model.Portal;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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

        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;

        Player player = event.getPlayer();

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
            if (!success) return;
            if (plugin.getConfig().getBoolean("teleport-message", true)) {
                String msg = plugin.getConfig()
                        .getString("teleport-message-text", "&aYou have been teleported to &b{world}&a!")
                        .replace("{world}", portal.getDestinationWorld())
                        .replace("&", "§");
                player.sendMessage(msg);
            }
            // Game mode is applied by PlayerChangedWorldEvent
        });
    }

    // ---- Per-world game mode & spawn ----

    /**
     * Apply the world's default game mode when a player joins the server.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        applyWorldSettings(event.getPlayer(), event.getPlayer().getWorld().getName());
    }

    /**
     * Apply the world's default game mode whenever a player switches worlds
     * (covers portal teleports, /portal loadworld, etc.).
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        applyWorldSettings(event.getPlayer(), event.getPlayer().getWorld().getName());
    }

    /**
     * Override the respawn location if the world has a custom spawn point set.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // Don't override bed/anchor respawns — they represent an explicit player choice
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;

        String worldName = event.getPlayer().getWorld().getName();
        WorldConfigManager.WorldSpawn spawn = plugin.getWorldConfigManager().getSpawn(worldName);
        if (spawn == null) return;

        World world = event.getPlayer().getWorld();
        event.setRespawnLocation(spawn.toLocation(world));
    }

    // ---- Per-world PvP ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        // Resolve the actual attacker (direct hit or projectile shooter)
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null) return;

        String worldName = victim.getWorld().getName();
        Boolean pvp = plugin.getWorldConfigManager().getPvp(worldName);
        if (pvp != null && !pvp) {
            event.setCancelled(true);
            attacker.sendMessage("§c[Portals] PvP is disabled in this world.");
        }
    }

    // ---- Internal helpers ----

    private void applyWorldSettings(Player player, String worldName) {
        GameMode gm = plugin.getWorldConfigManager().getGameMode(worldName);
        if (gm != null) {
            player.setGameMode(gm);
        }

        if (plugin.getWorldConfigManager().isClearInventory(worldName)) {
            player.getInventory().clear();
        }
    }
}
