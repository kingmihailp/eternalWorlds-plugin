package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.model.Portal;
import com.eternalworlds.portals.util.ColorUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages repeating enable/disable cycles for portals.
 *
 * Full cycle:
 *   1. Enable portal  → broadcast open-message
 *   2. Wait enableSeconds
 *   3. Disable portal → broadcast close-message, start item randomization
 *   4. Wait disableSeconds
 *   5. Stop randomization, send end-message to players in dest world
 *   6. Wait 3 seconds (so players can read the message)
 *   7. Teleport players in dest world to winners-dest world
 *   8. Enable portal  → broadcast open-message  [repeat from step 2]
 */
public class PortalSchedulerManager {

    /** Ticks to wait after end-message before teleporting players to winners dest. */
    private static final long END_GRACE_TICKS = 60L; // 3 seconds

    private final EternalWorldsPlugin plugin;
    /** portal name (lower-case) -> currently pending phase task */
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public PortalSchedulerManager(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts (or restarts) the cycle for the given portal.
     * Immediately enables the portal and broadcasts the open-message.
     */
    public void startCycle(String portalName, int enableSeconds, int disableSeconds) {
        stopCycle(portalName);

        Portal portal = plugin.getPortalManager().getPortal(portalName);
        if (portal == null) return;

        String destWorld = portal.getDestinationWorld();

        // Phase 1 start: enable portal, stop item randomization
        setPortalEnabled(portalName, true);
        plugin.getItemRandomizationManager().stopRandomization(destWorld);
        broadcastGlobal(plugin.getMinigameConfigManager().getOpenMessage(portalName), portalName, destWorld);

        scheduleNextPhase(portalName, destWorld, enableSeconds, disableSeconds, true);
    }

    /** Stops the cycle and leaves the portal in its current state. */
    public void stopCycle(String portalName) {
        BukkitTask t = tasks.remove(portalName.toLowerCase());
        if (t != null) t.cancel();
    }

    public boolean isRunning(String portalName) {
        return tasks.containsKey(portalName.toLowerCase());
    }

    public void cancelAll() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
    }

    // ---- Internal ----

    private void scheduleNextPhase(String portalName, String destWorld,
                                   int enableSec, int disableSec, boolean portalCurrentlyEnabled) {
        int delayTicks = (portalCurrentlyEnabled ? enableSec : disableSec) * 20;

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {

            if (portalCurrentlyEnabled) {
                // ── Enable phase ended: close the portal ──────────────────────────
                broadcastGlobal(plugin.getMinigameConfigManager().getCloseMessage(portalName),
                        portalName, destWorld);
                setPortalEnabled(portalName, false);

                if (plugin.getWorldConfigManager().isItemRandomizationEnabled(destWorld)) {
                    plugin.getItemRandomizationManager().startRandomization(destWorld);
                }

                scheduleNextPhase(portalName, destWorld, enableSec, disableSec, false);

            } else {
                // ── Disable phase ended: game over ────────────────────────────────
                plugin.getItemRandomizationManager().stopRandomization(destWorld);

                // 1. Send end-message to every player in the game world
                broadcastToWorld(destWorld,
                        plugin.getMinigameConfigManager().getEndMessage(portalName),
                        portalName, destWorld);

                // 2. After grace period: teleport to winners dest, then re-open portal
                BukkitTask endTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {

                    teleportToWinnersDest(portalName, destWorld);

                    setPortalEnabled(portalName, true);
                    broadcastGlobal(plugin.getMinigameConfigManager().getOpenMessage(portalName),
                            portalName, destWorld);

                    scheduleNextPhase(portalName, destWorld, enableSec, disableSec, true);

                }, END_GRACE_TICKS);

                // Track the inner task so stopCycle() can cancel it during the grace period
                tasks.put(portalName.toLowerCase(), endTask);
            }

        }, delayTicks);

        tasks.put(portalName.toLowerCase(), task);
    }

    // ---- Helpers ----

    private void teleportToWinnersDest(String portalName, String gameWorldName) {
        String winnersDest = plugin.getMinigameConfigManager().getWinnersDest(portalName);
        if (winnersDest == null) return;

        World winnersWorld = plugin.getWorldManager().loadWorld(winnersDest);
        if (winnersWorld == null) {
            plugin.getLogger().warning("[Portals] Winners-dest world '" + winnersDest
                    + "' for portal '" + portalName + "' could not be loaded.");
            return;
        }

        WorldConfigManager.WorldSpawn spawn = plugin.getWorldConfigManager().getSpawn(winnersDest);
        Location dest = spawn != null ? spawn.toLocation(winnersWorld) : winnersWorld.getSpawnLocation();

        World gameWorld = plugin.getServer().getWorld(gameWorldName);
        if (gameWorld == null) return;

        for (Player p : new ArrayList<>(gameWorld.getPlayers())) {
            p.teleportAsync(dest);
        }
    }

    /**
     * Broadcasts a message to all players on the server.
     * Supports hex colors via ColorUtil. No-op if message is null.
     */
    private void broadcastGlobal(String message, String portalName, String worldName) {
        if (message == null) return;
        plugin.getServer().broadcastMessage(buildMessage(message, portalName, worldName));
    }

    /**
     * Sends a message to all players currently in the given world.
     * No-op if message is null or world is not loaded.
     */
    private void broadcastToWorld(String worldName, String message,
                                  String portalName, String worldDisplayName) {
        if (message == null) return;
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) return;
        String parsed = buildMessage(message, portalName, worldDisplayName);
        for (Player p : world.getPlayers()) {
            p.sendMessage(parsed);
        }
    }

    private String buildMessage(String raw, String portalName, String worldName) {
        return ColorUtil.parse(raw
                .replace("{portal}", portalName)
                .replace("{world}",  worldName));
    }

    private void setPortalEnabled(String portalName, boolean enabled) {
        Portal portal = plugin.getPortalManager().getPortal(portalName);
        if (portal != null && portal.isEnabled() != enabled) {
            portal.setEnabled(enabled);
            plugin.getPortalManager().savePortals();
        }
    }
}
