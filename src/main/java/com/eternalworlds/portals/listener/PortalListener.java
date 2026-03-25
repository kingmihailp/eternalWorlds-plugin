package com.eternalworlds.portals.listener;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.manager.SelectionManager;
import com.eternalworlds.portals.manager.WorldConfigManager;
import com.eternalworlds.portals.model.Portal;
import org.bukkit.GameMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PortalListener implements Listener {

    private final EternalWorldsPlugin plugin;

    /** Tracks when each player last used a portal (epoch ms). */
    private final Map<UUID, Long>   portalCooldowns     = new HashMap<>();
    /** Prevents repeated elimination triggers while falling. */
    private final Set<UUID>         eliminationPending  = new HashSet<>();
    /** Players who disconnected from a leavable world and must be teleported on next join. */
    private final Map<UUID, String> pendingLeavableTp   = new HashMap<>();

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

        Location loc    = event.getClickedBlock().getLocation();
        Action   action = event.getAction();

        if (action == Action.LEFT_CLICK_BLOCK) {
            plugin.getSelectionManager().setPos1(player, loc);
            event.setCancelled(true);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            plugin.getSelectionManager().setPos2(player, loc);
            event.setCancelled(true);
        }
    }

    // ---- Portal teleportation + Y-level elimination ----

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        // Let vanilla handle its own portal blocks — our logic must not interfere.
        // PlayerMoveEvent fires BEFORE PlayerPortalEvent, so without this check
        // our custom portal / Y-elimination logic would run first and teleport
        // the player to the wrong world.
        Material toBlock = to.getBlock().getType();
        if (toBlock == Material.END_PORTAL || toBlock == Material.NETHER_PORTAL) return;

        Player player = event.getPlayer();

        // -- Y-level elimination check (only when Y actually changes) --
        if (from.getBlockY() != to.getBlockY() && !eliminationPending.contains(player.getUniqueId())) {
            WorldConfigManager.EliminationConfig ec =
                    plugin.getWorldConfigManager().getEliminationConfig(to.getWorld().getName());
            if (ec != null && to.getY() <= ec.yLevel()) {
                eliminationPending.add(player.getUniqueId());
                World targetWorld = plugin.getWorldManager().loadWorld(ec.targetWorld());
                if (targetWorld != null) {
                    WorldConfigManager.WorldSpawn spawn =
                            plugin.getWorldConfigManager().getSpawn(ec.targetWorld());
                    Location dest = spawn != null
                            ? spawn.toLocation(targetWorld)
                            : targetWorld.getSpawnLocation();
                    player.teleportAsync(dest).thenAccept(success -> {
                        eliminationPending.remove(player.getUniqueId());
                        if (success) {
                            player.sendMessage("§c[Portals] You have been eliminated!");
                        }
                    });
                } else {
                    eliminationPending.remove(player.getUniqueId());
                }
                return;
            }
        }

        // -- Portal entry check (only when block position changes) --
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;

        if (!plugin.getConfig().getBoolean("allow-player-use", true)
                && !player.hasPermission("eternalworlds.portal.admin")) return;
        if (!player.hasPermission("eternalworlds.portal.use")) return;

        Portal portal = plugin.getPortalManager().getPortalAt(
                to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (portal == null) {
            // No enabled portal here — check if a disabled portal allows spectator re-entry.
            trySpectatorEntry(player, to);
            return;
        }

        // Cooldown check
        int  cooldownSec = plugin.getConfig().getInt("portal-cooldown", 3);
        long now         = System.currentTimeMillis();
        Long lastUsed    = portalCooldowns.get(player.getUniqueId());
        if (lastUsed != null && now - lastUsed < cooldownSec * 1000L) return;
        portalCooldowns.put(player.getUniqueId(), now);

        // Determine destination — prefer random spawn points if configured
        Location destination;

        if (plugin.getRandomPointManager().hasPoints(portal.getName())) {
            Location randomPoint = plugin.getRandomPointManager()
                    .getRandomAvailablePoint(portal.getName());
            if (randomPoint == null) {
                // All points occupied — portal is at capacity
                player.sendMessage("§c[Portals] All spawn points are occupied. Please wait.");
                return;
            }
            destination = randomPoint;
        } else {
            World destWorld = plugin.getWorldManager().loadWorld(portal.getDestinationWorld());
            if (destWorld == null) {
                player.sendMessage("§c[Portals] Destination world '"
                        + portal.getDestinationWorld() + "' could not be loaded.");
                return;
            }
            destination = new Location(destWorld,
                    portal.getDestX(), portal.getDestY(), portal.getDestZ(),
                    portal.getDestYaw(), portal.getDestPitch());
        }

        player.teleportAsync(destination).thenAccept(success -> {
            if (!success) return;
            if (plugin.getConfig().getBoolean("teleport-message", true)) {
                String msg = plugin.getConfig()
                        .getString("teleport-message-text",
                                "&aYou have been teleported to &b{world}&a!")
                        .replace("{world}", portal.getDestinationWorld())
                        .replace("&", "§");
                player.sendMessage(msg);
            }
            // Game mode and inventory clear are handled by PlayerChangedWorldEvent
        });
    }

    // ---- Per-world game mode & inventory clear on world change ----

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String targetWorld = pendingLeavableTp.remove(player.getUniqueId());
        if (targetWorld != null) {
            World dest = plugin.getWorldManager().loadWorld(targetWorld);
            if (dest != null) {
                WorldConfigManager.WorldSpawn spawn =
                        plugin.getWorldConfigManager().getSpawn(targetWorld);
                Location tpDest = spawn != null
                        ? spawn.toLocation(dest)
                        : dest.getSpawnLocation();
                // Schedule 1 tick later so the player is fully loaded before teleport
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> player.teleportAsync(tpDest), 1L);
            }
        }
        applyWorldSettings(player, player.getWorld().getName());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player        = event.getPlayer();
        String currentWorld  = player.getWorld().getName();
        String leavableTarget = plugin.getWorldConfigManager().getLeavable(currentWorld);
        if (leavableTarget != null) {
            pendingLeavableTp.put(player.getUniqueId(), leavableTarget);
        }
        // Always clean up freeze state on disconnect.
        plugin.getPlayerFreezeManager().unfreeze(player);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player        = event.getPlayer();
        String fromWorldName = event.getFrom().getName();
        String newWorldName  = player.getWorld().getName();

        // Unfreeze the player when they leave a frozen game world.
        if (plugin.getDynamicDelayManager().isWorldFreezeActive(fromWorldName)) {
            plugin.getPlayerFreezeManager().unfreeze(player);
        }
        // Freeze the player if they enter a game world that is in WAITING/COUNTDOWN.
        if (plugin.getDynamicDelayManager().isWorldFreezeActive(newWorldName)) {
            plugin.getPlayerFreezeManager().freeze(player);
        }

        // If the world the player just left has a "leavable" target configured,
        // send them there — unless they already landed in that target world.
        String leavableTarget = plugin.getWorldConfigManager().getLeavable(fromWorldName);
        if (leavableTarget != null && !newWorldName.equalsIgnoreCase(leavableTarget)) {
            World dest = plugin.getWorldManager().loadWorld(leavableTarget);
            if (dest != null) {
                WorldConfigManager.WorldSpawn spawn =
                        plugin.getWorldConfigManager().getSpawn(leavableTarget);
                Location tpDest = spawn != null
                        ? spawn.toLocation(dest)
                        : dest.getSpawnLocation();
                player.teleportAsync(tpDest);
            }
        }

        applyWorldSettings(player, newWorldName);
    }

    // ---- Custom respawn location ----

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;

        String worldName = event.getPlayer().getWorld().getName();
        WorldConfigManager.WorldSpawn spawn = plugin.getWorldConfigManager().getSpawn(worldName);
        if (spawn == null) return;

        event.setRespawnLocation(spawn.toLocation(event.getPlayer().getWorld()));
    }

    // ---- Bed sleeping ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        if (!plugin.getWorldConfigManager().isBedSleepingAllowed(event.getPlayer().getWorld().getName())) {
            event.setUseBed(org.bukkit.event.Event.Result.DENY);
            event.getPlayer().sendMessage("§c[Portals] Sleeping is disabled in this world.");
        }
    }

    // ---- Building height limit ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        String worldName = event.getBlock().getWorld().getName();
        Integer maxY = plugin.getWorldConfigManager().getBuildingHeight(worldName);
        if (maxY == null) return;
        if (event.getBlock().getY() > maxY) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    "§c[Portals] You cannot place blocks above Y=" + maxY + " in this world.");
        }
    }

    // ---- Nether / End portal blocking ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Player player    = event.getPlayer();
        String worldName = player.getWorld().getName();

        switch (event.getCause()) {
            case NETHER_PORTAL -> {
                if (!plugin.getWorldConfigManager().isNetherAllowed(worldName)) {
                    event.setCancelled(true);
                    player.sendMessage("§c[Portals] Нether portals are disabled in this world.");
                }
            }
            case END_PORTAL, END_GATEWAY -> {
                if (!plugin.getWorldConfigManager().isEndAllowed(worldName)) {
                    event.setCancelled(true);
                    player.sendMessage("§c[Portals] End portals are disabled in this world.");
                }
            }
            default -> { /* other causes — no restriction */ }
        }
    }

    // ---- Per-world PvP ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null) return;

        Boolean pvp = plugin.getWorldConfigManager().getPvp(victim.getWorld().getName());
        if (pvp != null && !pvp) {
            event.setCancelled(true);
            attacker.sendMessage("§c[Portals] PvP is disabled in this world.");
        }
    }

    // ---- Internal helpers ----

    /**
     * Handles spectator re-entry: if the player steps into a disabled portal that
     * has an active GAME_RUNNING dynamic-delay cycle and the player was an original
     * participant of that game, they are teleported to the game world as a spectator.
     */
    private void trySpectatorEntry(Player player, Location to) {
        Portal portal = plugin.getPortalManager().getDisabledPortalAt(
                to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (portal == null) return;

        String portalName = portal.getName();
        if (!plugin.getDynamicDelayManager().isPhaseGameRunning(portalName)) return;
        if (!plugin.getDynamicDelayManager().isOriginalParticipant(portalName, player.getUniqueId())) return;

        // Respect the same cooldown as regular portal use.
        int  cooldownSec = plugin.getConfig().getInt("portal-cooldown", 3);
        long now         = System.currentTimeMillis();
        Long lastUsed    = portalCooldowns.get(player.getUniqueId());
        if (lastUsed != null && now - lastUsed < cooldownSec * 1000L) return;
        portalCooldowns.put(player.getUniqueId(), now);

        // Use the portal's fixed destination (not random spawn points).
        World destWorld = plugin.getWorldManager().loadWorld(portal.getDestinationWorld());
        if (destWorld == null) return;
        Location dest = new Location(destWorld,
                portal.getDestX(), portal.getDestY(), portal.getDestZ(),
                portal.getDestYaw(), portal.getDestPitch());

        player.teleportAsync(dest).thenAccept(success -> {
            if (!success) return;
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage("§7[Portals] You rejoined §e" + portalName + " §7as a §fspectator§7.");
        });
    }

    private void applyWorldSettings(Player player, String worldName) {
        GameMode gm = plugin.getWorldConfigManager().getGameMode(worldName);
        if (gm != null) player.setGameMode(gm);

        if (plugin.getWorldConfigManager().isClearInventory(worldName)) {
            player.getInventory().clear();
        }
    }
}
