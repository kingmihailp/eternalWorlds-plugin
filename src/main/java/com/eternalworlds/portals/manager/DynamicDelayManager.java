package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.model.Portal;
import com.eternalworlds.portals.util.ColorUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages "dynamic delay" portals.
 *
 * Behaviour:
 *  1. Portal starts (and stays) ENABLED while player count on the server is 0 or 1.
 *  2. As soon as 2+ players are online the portal closes and the game timer begins.
 *  3. During the game the manager checks the portal's destination world every second:
 *     if only 1 player remains they are counted as the survivor and the round ends early.
 *  4. When the round ends (timer or last-survivor) players in the game world are sent to
 *     the configured winners world.  The world is cleaned if cleaning is enabled.
 *  5. The portal re-opens and the manager returns to step 1.
 *
 * Configuration is persisted to <plugin-folder>/dynamic-delay.yml.
 */
public class DynamicDelayManager {

    /** How often to poll the online player count while waiting (ticks). */
    private static final long POLL_TICKS      = 40L;  // 2 s
    /** Grace period after the end-message before teleporting winners (ticks). */
    private static final long END_GRACE_TICKS = 60L;  // 3 s

    /** Persistent config stored per portal. */
    public record DynamicConfig(int gameSeconds, String winnersWorld) {}

    private enum Phase { WAITING, GAME_RUNNING, GAME_ENDING }

    private final EternalWorldsPlugin plugin;
    private final File                dataFile;

    /** portalName (lower-case) → persisted config */
    private final Map<String, DynamicConfig> configs = new HashMap<>();
    /** Task key → active Bukkit task.  Game-monitor task uses key "<portal>:monitor". */
    private final Map<String, BukkitTask>    tasks   = new HashMap<>();
    /** portalName (lower-case) → current lifecycle phase */
    private final Map<String, Phase>         phases  = new HashMap<>();

    public DynamicDelayManager(EternalWorldsPlugin plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "dynamic-delay.yml");
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public void load() {
        configs.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        if (!cfg.isConfigurationSection("portals")) return;
        for (String key : cfg.getConfigurationSection("portals").getKeys(false)) {
            String path    = "portals." + key;
            int    gameSec = cfg.getInt(path + ".game-seconds",  120);
            String world   = cfg.getString(path + ".winners-world", "world");
            configs.put(key.toLowerCase(), new DynamicConfig(gameSec, world));
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        configs.forEach((name, dc) -> {
            String path = "portals." + name;
            cfg.set(path + ".game-seconds",  dc.gameSeconds());
            cfg.set(path + ".winners-world", dc.winnersWorld());
        });
        try {
            cfg.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[Portals] Could not save dynamic-delay.yml: " + e.getMessage());
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Activates dynamic-delay mode for the portal.
     * Any regular scheduler cycle for this portal is stopped first.
     */
    public void startDynamic(String portalName, int gameSeconds, String winnersWorld) {
        String key = portalName.toLowerCase();
        configs.put(key, new DynamicConfig(gameSeconds, winnersWorld));
        save();
        // A regular cycle and a dynamic cycle must not run simultaneously.
        plugin.getPortalSchedulerManager().stopCycle(portalName);
        startWaiting(key);
    }

    /** Returns true if this portal has dynamic-delay mode configured. */
    public boolean hasDynamic(String portalName) {
        return configs.containsKey(portalName.toLowerCase());
    }

    /** Stops and removes dynamic-delay mode for the portal. */
    public void stopDynamic(String portalName) {
        String key = portalName.toLowerCase();
        configs.remove(key);
        cancelTask(key);
        cancelTask(key + ":monitor");
        phases.remove(key);
        save();
    }

    /** Loads persisted config and restarts all dynamic portals (called on plugin enable). */
    public void loadAndRestart() {
        load();
        configs.keySet().forEach(this::startWaiting);
    }

    /** Cancels all running tasks (called on plugin disable). */
    public void cancelAll() {
        new ArrayList<>(tasks.keySet()).forEach(k -> {
            BukkitTask t = tasks.remove(k);
            if (t != null) t.cancel();
        });
        phases.clear();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Phase 1: portal is open, poll server player count every 2 seconds. */
    private void startWaiting(String key) {
        cancelTask(key);
        cancelTask(key + ":monitor");
        phases.put(key, Phase.WAITING);

        // Make sure the portal is open while we wait.
        Portal portal = plugin.getPortalManager().getPortal(key);
        if (portal != null && !portal.isEnabled()) {
            portal.setEnabled(true);
            plugin.getPortalManager().savePortals();
        }

        BukkitTask pollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phases.get(key) != Phase.WAITING) return;
            Portal p = plugin.getPortalManager().getPortal(key);
            if (p == null) return;
            World destWorld = plugin.getServer().getWorld(p.getDestinationWorld());
            if (destWorld != null && destWorld.getPlayers().size() >= 2) {
                beginGame(key);
            }
        }, POLL_TICKS, POLL_TICKS);
        tasks.put(key, pollTask);
    }

    /** Phase 2: close the portal, start the game timer, monitor for last survivor. */
    private void beginGame(String key) {
        DynamicConfig dc = configs.get(key);
        if (dc == null) return;
        cancelTask(key);
        phases.put(key, Phase.GAME_RUNNING);

        Portal portal = plugin.getPortalManager().getPortal(key);
        if (portal == null) { startWaiting(key); return; }

        String gameWorldName = portal.getDestinationWorld();

        // Close the portal.
        portal.setEnabled(false);
        plugin.getPortalManager().savePortals();

        // Broadcast the close message.
        String closeMsg = plugin.getMinigameConfigManager().getCloseMessage(key);
        if (closeMsg != null) {
            plugin.getServer().broadcastMessage(ColorUtil.parse(
                closeMsg.replace("{portal}", portal.getName())
                        .replace("{world}",  gameWorldName)));
        }

        // Start item randomization if it is enabled for the game world.
        if (plugin.getWorldConfigManager().isItemRandomizationEnabled(gameWorldName)) {
            plugin.getItemRandomizationManager().startRandomization(gameWorldName);
        }

        // Monitor the game world for the last survivor (checked every second).
        BukkitTask monitorTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phases.get(key) != Phase.GAME_RUNNING) return;
            World gameWorld = plugin.getServer().getWorld(gameWorldName);
            if (gameWorld == null) return;
            if (gameWorld.getPlayers().size() <= 1) {
                beginEnding(key, gameWorldName, dc.winnersWorld());
            }
        }, 20L, 20L);
        tasks.put(key + ":monitor", monitorTask);

        // Schedule the normal game-over after gameSeconds.
        BukkitTask endTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (phases.get(key) == Phase.GAME_RUNNING) {
                beginEnding(key, gameWorldName, dc.winnersWorld());
            }
        }, dc.gameSeconds() * 20L);
        tasks.put(key, endTask);
    }

    /** Phase 3: send the end message, wait the grace period, teleport winners, reset. */
    private void beginEnding(String key, String gameWorldName, String winnersWorldName) {
        if (phases.get(key) == Phase.GAME_ENDING) return; // already ending
        phases.put(key, Phase.GAME_ENDING);
        cancelTask(key);
        cancelTask(key + ":monitor");

        // Stop item randomization in the game world.
        plugin.getItemRandomizationManager().stopRandomization(gameWorldName);

        // Broadcast the end message to remaining players.
        String endMsg = plugin.getMinigameConfigManager().getEndMessage(key);
        if (endMsg != null) {
            World gameWorld = plugin.getServer().getWorld(gameWorldName);
            if (gameWorld != null) {
                Portal portal  = plugin.getPortalManager().getPortal(key);
                String pName   = portal != null ? portal.getName() : key;
                String parsed  = ColorUtil.parse(
                    endMsg.replace("{portal}", pName)
                          .replace("{world}",  gameWorldName));
                for (Player p : gameWorld.getPlayers()) p.sendMessage(parsed);
            }
        }

        // After grace period: teleport winners, optionally clean world, then re-open portal.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            World gameWorld    = plugin.getServer().getWorld(gameWorldName);
            World winnersWorld = plugin.getWorldManager().loadWorld(winnersWorldName);

            // Teleport everyone remaining in the game world to the winners world.
            if (gameWorld != null && winnersWorld != null) {
                WorldConfigManager.WorldSpawn spawn =
                        plugin.getWorldConfigManager().getSpawn(winnersWorldName);
                Location dest = spawn != null
                        ? spawn.toLocation(winnersWorld)
                        : winnersWorld.getSpawnLocation();
                for (Player p : new ArrayList<>(gameWorld.getPlayers())) {
                    p.teleportAsync(dest);
                }
            }

            // Clean the game world if the admin has enabled it.
            if (gameWorld != null && plugin.getWorldConfigManager().isCleaningEnabled(gameWorldName)) {
                plugin.getPortalSchedulerManager().cleanWorld(gameWorld);
            }

            // Short pause before the portal re-opens so cleanWorld can start its async loop.
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> startWaiting(key), 20L);

        }, END_GRACE_TICKS);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void cancelTask(String key) {
        BukkitTask t = tasks.remove(key);
        if (t != null) t.cancel();
    }
}
