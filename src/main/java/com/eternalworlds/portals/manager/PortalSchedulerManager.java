package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.model.Portal;
import com.eternalworlds.portals.util.ColorUtil;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages repeating enable/disable cycles for portals.
 *
 * Full cycle:
 *   1. Enable portal  → broadcast open-message
 *   2. Wait enableSeconds
 *   3. Disable portal → broadcast close-message, start item randomization
 *   4. Wait disableSeconds
 *   5. Stop randomization, send end-message to players in dest world
 *   6. Wait 3 seconds (grace period to read the message)
 *   7. If dest world has cleaning enabled → clean it (entities + blocks)
 *   8. Teleport players in dest world to winners-dest world
 *   9. Enable portal  → broadcast open-message  [repeat from step 2]
 *
 * Active cycles are persisted in <plugin-folder>/scheduler.yml so they
 * survive server restarts.
 */
public class PortalSchedulerManager {

    /** Ticks to wait after end-message before teleporting players to winners dest. */
    private static final long END_GRACE_TICKS = 60L; // 3 seconds

    /** Radius (blocks from world origin) to clean. */
    private static final int CLEAN_RADIUS = 1000;
    /** Chunks processed per tick during world cleaning. */
    private static final int CHUNKS_PER_TICK = 3;

    private final EternalWorldsPlugin plugin;
    private final File schedulerFile;

    /** portal name (lower-case) -> currently pending phase task */
    private final Map<String, BukkitTask> tasks = new HashMap<>();
    /** world name (lower-case) -> active block-cleaning task for that world */
    private final Map<String, BukkitTask> cleaningTasks = new HashMap<>();

    public PortalSchedulerManager(EternalWorldsPlugin plugin) {
        this.plugin        = plugin;
        this.schedulerFile = new File(plugin.getDataFolder(), "scheduler.yml");
    }

    // ---- Public API ----

    /**
     * Starts (or restarts) the cycle for the given portal.
     * Immediately enables the portal and broadcasts the open-message.
     * The cycle configuration is saved to scheduler.yml.
     */
    public void startCycle(String portalName, int enableSeconds, int disableSeconds) {
        stopCycle(portalName);

        Portal portal = plugin.getPortalManager().getPortal(portalName);
        if (portal == null) return;

        String destWorld = portal.getDestinationWorld();

        // Persist the cycle so it can be restored after a restart
        saveCycle(portalName, enableSeconds, disableSeconds);

        // Phase 1 start: enable portal, stop item randomization, announce
        setPortalEnabled(portalName, true);
        plugin.getItemRandomizationManager().stopRandomization(destWorld);
        broadcastGlobal(plugin.getMinigameConfigManager().getOpenMessage(portalName), portalName, destWorld);

        scheduleNextPhase(portalName, destWorld, enableSeconds, disableSeconds, true);
    }

    /** Stops the cycle and removes it from scheduler.yml. */
    public void stopCycle(String portalName) {
        BukkitTask t = tasks.remove(portalName.toLowerCase());
        if (t != null) t.cancel();
        removeCycle(portalName);
    }

    public boolean isRunning(String portalName) {
        return tasks.containsKey(portalName.toLowerCase());
    }

    public void cancelAll() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
        cleaningTasks.values().forEach(BukkitTask::cancel);
        cleaningTasks.clear();
        // Note: we deliberately do NOT wipe scheduler.yml on cancelAll() —
        // that is called on server shutdown and the cycles should resume on next start.
    }

    /**
     * Reads scheduler.yml and restarts all previously active cycles.
     * Called from EternalWorldsPlugin.onEnable() after portals are loaded.
     *
     * If world-cleaning is configured for a portal's destination world the world is
     * cleaned first; the cycle starts only after cleaning completes.  This prevents
     * a game from beginning on a map that was left dirty by a shutdown mid-game or
     * mid-clean.
     */
    public void loadAndRestartCycles() {
        if (!schedulerFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(schedulerFile);
        if (!cfg.isConfigurationSection("active-cycles")) return;

        for (String portalName : cfg.getConfigurationSection("active-cycles").getKeys(false)) {
            int enableSec  = cfg.getInt("active-cycles." + portalName + ".enable-seconds", 0);
            int disableSec = cfg.getInt("active-cycles." + portalName + ".disable-seconds", 0);
            if (enableSec <= 0 || disableSec <= 0) continue;

            Portal portal = plugin.getPortalManager().getPortal(portalName);
            if (portal == null) {
                plugin.getLogger().warning("[Portals] Scheduler: portal '" + portalName
                        + "' no longer exists, skipping cycle restore.");
                continue;
            }
            plugin.getLogger().info("[Portals] Restoring cycle for portal '" + portalName
                    + "' (" + enableSec + "s open / " + disableSec + "s closed).");

            String destWorld = portal.getDestinationWorld();
            if (plugin.getWorldConfigManager().isCleaningEnabled(destWorld)) {
                World gameWorld = plugin.getServer().getWorld(destWorld);
                if (gameWorld != null) {
                    final String pName = portalName;
                    final int    eSec  = enableSec;
                    final int    dSec  = disableSec;
                    cleanWorld(gameWorld, () -> startCycle(pName, eSec, dSec));
                    continue;
                }
            }
            startCycle(portalName, enableSec, disableSec);
        }
    }

    // ---- Internal scheduling ----

    private void scheduleNextPhase(String portalName, String destWorld,
                                   int enableSec, int disableSec, boolean portalCurrentlyEnabled) {
        int delayTicks = (portalCurrentlyEnabled ? enableSec : disableSec) * 20;

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {

            if (portalCurrentlyEnabled) {
                // ── Enable phase ended: close the portal ──────────────────────
                broadcastGlobal(plugin.getMinigameConfigManager().getCloseMessage(portalName),
                        portalName, destWorld);
                setPortalEnabled(portalName, false);

                if (plugin.getWorldConfigManager().isItemRandomizationEnabled(destWorld)) {
                    plugin.getItemRandomizationManager().startRandomization(destWorld);
                }

                scheduleNextPhase(portalName, destWorld, enableSec, disableSec, false);

            } else {
                // ── Disable phase ended: game over ────────────────────────────
                plugin.getItemRandomizationManager().stopRandomization(destWorld);

                // Send end-message to every player still in the game world
                broadcastToWorld(destWorld,
                        plugin.getMinigameConfigManager().getEndMessage(portalName),
                        portalName, destWorld);

                // After grace period: clean world (if enabled), teleport, re-open portal
                BukkitTask endTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {

                    // 1. Clean the game world (entities + blocks) if configured
                    if (plugin.getWorldConfigManager().isCleaningEnabled(destWorld)) {
                        World gameWorld = plugin.getServer().getWorld(destWorld);
                        if (gameWorld != null) {
                            cleanWorld(gameWorld);
                        }
                    }

                    // 2. Teleport players to winners destination
                    teleportToWinnersDest(portalName, destWorld);

                    // 3. Re-enable portal and announce
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

    // ---- Winners teleport ----

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

    // ---- World cleaning ----

    /**
     * Cleans the given world within CLEAN_RADIUS blocks of (0, 0):
     *   1. Removes all non-player entities (mobs, dropped items, etc.) — synchronous, instant.
     *   2. Replaces all non-bedrock, non-air blocks in loaded chunks with air —
     *      processed CHUNKS_PER_TICK chunks per tick to avoid server lag.
     */
    public void cleanWorld(World world) {
        cleanWorld(world, null);
    }

    /**
     * Same as {@link #cleanWorld(World)} but runs {@code onComplete} on the main thread
     * once all chunks have been processed.  Pass {@code null} if no callback is needed.
     * The callback is also invoked immediately when there are no loaded chunks to clean.
     */
    public void cleanWorld(World world, Runnable onComplete) {
        String worldKey = world.getName().toLowerCase();

        // Cancel any in-progress clean task for this world before starting a new one.
        BukkitTask prev = cleaningTasks.remove(worldKey);
        if (prev != null) prev.cancel();

        // 1. Remove entities within radius
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Player) continue;
            Location loc = entity.getLocation();
            if (Math.abs(loc.getX()) <= CLEAN_RADIUS && Math.abs(loc.getZ()) <= CLEAN_RADIUS) {
                entity.remove();
            }
        }

        // 2. Gather currently loaded chunks within the radius
        int chunkRadius = (CLEAN_RADIUS / 16) + 1;
        List<Chunk> chunks = new ArrayList<>();

        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                if (Math.sqrt((double) cx * cx + (double) cz * cz) * 16 > CLEAN_RADIUS + 16) continue;
                if (world.isChunkLoaded(cx, cz)) {
                    chunks.add(world.getChunkAt(cx, cz));
                }
            }
        }

        if (chunks.isEmpty()) {
            // Nothing to clean — fire the callback immediately on the current (main) thread.
            if (onComplete != null) onComplete.run();
            return;
        }

        AtomicInteger index = new AtomicInteger(0);

        BukkitTask cleanTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int processed = 0;
            while (index.get() < chunks.size() && processed < CHUNKS_PER_TICK) {
                Chunk chunk = chunks.get(index.getAndIncrement());
                try {
                    clearChunk(chunk);
                } catch (Exception e) {
                    plugin.getLogger().warning("[Portals] Error cleaning chunk ("
                            + chunk.getX() + "," + chunk.getZ() + ") in '"
                            + world.getName() + "': " + e.getMessage());
                }
                processed++;
            }
            if (index.get() >= chunks.size()) {
                BukkitTask self = cleaningTasks.remove(worldKey);
                if (self != null) self.cancel();
                // Fire the callback now that all chunks have been processed.
                if (onComplete != null) onComplete.run();
            }
        }, 1L, 1L);

        cleaningTasks.put(worldKey, cleanTask);
        plugin.getLogger().info("[Portals] Cleaning world '" + world.getName()
                + "': " + chunks.size() + " chunks to process.");
    }

    /**
     * Replaces every non-bedrock, non-air block in the chunk with air (no physics update).
     * Iterates from the world's minimum height so that player-placed blocks in the
     * natural bedrock zone are also removed; actual bedrock is skipped by the material
     * check inside the loop, making a hard Y-offset unnecessary.
     * After clearing, the chunk is refreshed for all nearby clients to prevent phantom
     * blocks that arise when bulk block changes are applied without neighbour updates.
     */
    private void clearChunk(Chunk chunk) {
        World world = chunk.getWorld();
        int minY    = world.getMinHeight();
        int maxY    = world.getMaxHeight();
        // Use the configured lower bound if provided; otherwise start from the world minimum
        // so that every non-bedrock block (including those below the natural bedrock zone)
        // is reached. The BEDROCK material check below already protects all bedrock blocks.
        Integer configMinY = plugin.getWorldConfigManager().getCleanMinY(world.getName());
        int startY = (configMinY != null) ? configMinY : minY;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = startY; y < maxY; y++) {
                    var block = chunk.getBlock(x, y, z);
                    Material type = block.getType();
                    if (type != Material.BEDROCK && !block.isEmpty()) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }

        // Resend chunk data to all players who have this chunk loaded.
        // setType(..., false) skips neighbour block-update packets, so bedrock blocks
        // adjacent to cleared blocks do not receive a face-cull refresh and can appear
        // phantom on the client. A chunk refresh guarantees a consistent client state.
        world.refreshChunk(chunk.getX(), chunk.getZ());
    }

    // ---- Persistence helpers ----

    private void saveCycle(String portalName, int enableSec, int disableSec) {
        YamlConfiguration cfg = schedulerFile.exists()
                ? YamlConfiguration.loadConfiguration(schedulerFile)
                : new YamlConfiguration();
        String path = "active-cycles." + portalName.toLowerCase();
        cfg.set(path + ".enable-seconds",  enableSec);
        cfg.set(path + ".disable-seconds", disableSec);
        try {
            cfg.save(schedulerFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[Portals] Failed to save scheduler.yml: " + e.getMessage());
        }
    }

    private void removeCycle(String portalName) {
        if (!schedulerFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(schedulerFile);
        cfg.set("active-cycles." + portalName.toLowerCase(), null);
        try {
            cfg.save(schedulerFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[Portals] Failed to save scheduler.yml: " + e.getMessage());
        }
    }

    // ---- Broadcast helpers ----

    private void broadcastGlobal(String message, String portalName, String worldName) {
        if (message == null) return;
        plugin.getServer().broadcastMessage(buildMessage(message, portalName, worldName));
    }

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
