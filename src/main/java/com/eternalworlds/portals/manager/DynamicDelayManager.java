package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.model.Portal;
import com.eternalworlds.portals.util.ColorUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages "dynamic delay" portals.
 *
 * Behaviour:
 *  1. Portal starts (and stays) ENABLED while player count on the server is 0 or 1.
 *     ActionBar: §c§oожидание игроков…
 *  2. As soon as 2+ players are online the portal closes and the countdown begins.
 *     ActionBar: до начала игры осталось X секунд
 *  3. During the game the manager checks the portal's destination world every second:
 *     if only 1 player remains they are counted as the survivor and the round ends early.
 *     ActionBar: до конца игры осталось X секунд
 *  4. When the round ends (timer or last-survivor) winners are announced to all players
 *     and survivors are sent to the configured winners world.  The world is cleaned if
 *     cleaning is enabled.
 *  5. The portal re-opens and the manager returns to step 1.
 *
 * Configuration is persisted to <plugin-folder>/dynamic-delay.yml.
 */
public class DynamicDelayManager {

    /** How often to poll the online player count while waiting (ticks). */
    private static final long POLL_TICKS      = 40L;  // 2 s
    /** Grace period after the end-message before teleporting winners (ticks). */
    private static final long END_GRACE_TICKS = 60L;  // 3 s

    /** ActionBar text sent while waiting for players. */
    private static final String AB_WAITING =
            ColorUtil.parse("&#FF6B6B&oожидание игроков…");

    /** Persistent config stored per portal. */
    public record DynamicConfig(int countdownSeconds, int gameSeconds, String winnersWorld) {}

    private enum Phase { WAITING, COUNTDOWN, GAME_RUNNING, GAME_ENDING }

    private final EternalWorldsPlugin plugin;
    private final File                dataFile;

    /** portalName (lower-case) → persisted config */
    private final Map<String, DynamicConfig> configs = new HashMap<>();
    /**
     * Active Bukkit tasks, keyed by:
     *   "<portal>"          – main lifecycle task (poll / countdown / endTimer)
     *   "<portal>:monitor"  – game monitor + actionbar task
     *   "<portal>:actionbar"– waiting-phase actionbar task
     */
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
            int    cdSec   = cfg.getInt(path + ".countdown-seconds", 30);
            int    gameSec = cfg.getInt(path + ".game-seconds",      120);
            String world   = cfg.getString(path + ".winners-world",  "world");
            configs.put(key.toLowerCase(), new DynamicConfig(cdSec, gameSec, world));
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        configs.forEach((name, dc) -> {
            String path = "portals." + name;
            cfg.set(path + ".countdown-seconds", dc.countdownSeconds());
            cfg.set(path + ".game-seconds",      dc.gameSeconds());
            cfg.set(path + ".winners-world",     dc.winnersWorld());
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
    public void startDynamic(String portalName, int countdownSeconds, int gameSeconds, String winnersWorld) {
        String key = portalName.toLowerCase();
        configs.put(key, new DynamicConfig(countdownSeconds, gameSeconds, winnersWorld));
        save();
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
        cancelTask(key + ":actionbar");
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
        cancelTask(key + ":actionbar");
        phases.put(key, Phase.WAITING);

        Portal portal = plugin.getPortalManager().getPortal(key);
        if (portal != null && !portal.isEnabled()) {
            portal.setEnabled(true);
            plugin.getPortalManager().savePortals();
        }

        // Poll player count every 2 seconds.
        BukkitTask pollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phases.get(key) != Phase.WAITING) return;
            Portal p = plugin.getPortalManager().getPortal(key);
            if (p == null) return;
            World destWorld = plugin.getServer().getWorld(p.getDestinationWorld());
            if (destWorld != null && destWorld.getPlayers().size() >= 2) {
                startCountdown(key);
            }
        }, POLL_TICKS, POLL_TICKS);
        tasks.put(key, pollTask);

        // Send "ожидание игроков…" actionbar every second to players in destination world.
        BukkitTask abTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phases.get(key) != Phase.WAITING) return;
            Portal p = plugin.getPortalManager().getPortal(key);
            if (p == null) return;
            World destWorld = plugin.getServer().getWorld(p.getDestinationWorld());
            if (destWorld == null) return;
            for (Player player : destWorld.getPlayers()) {
                sendActionBar(player, AB_WAITING);
            }
        }, 20L, 20L);
        tasks.put(key + ":actionbar", abTask);
    }

    /** Phase 1b: countdown before the game starts; cancels back to WAITING if players drop below 2. */
    private void startCountdown(String key) {
        cancelTask(key);
        cancelTask(key + ":actionbar");
        phases.put(key, Phase.COUNTDOWN);

        DynamicConfig dc = configs.get(key);
        if (dc == null) { startWaiting(key); return; }

        Portal portal = plugin.getPortalManager().getPortal(key);
        if (portal == null) { startWaiting(key); return; }
        String gameWorldName = portal.getDestinationWorld();

        int[] remaining = { dc.countdownSeconds() };

        BukkitTask cdTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phases.get(key) != Phase.COUNTDOWN) return;

            World destWorld = plugin.getServer().getWorld(gameWorldName);
            if (destWorld == null || destWorld.getPlayers().size() < 2) {
                startWaiting(key);
                return;
            }

            if (remaining[0] <= 0) {
                beginGame(key);
                return;
            }

            String abText = ColorUtil.parse("&eдо начала игры осталось &c" + remaining[0] + " &eсек.");
            for (Player p : destWorld.getPlayers()) {
                sendActionBar(p, abText);
            }
            remaining[0]--;
        }, 20L, 20L);
        tasks.put(key, cdTask);
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

        portal.setEnabled(false);
        plugin.getPortalManager().savePortals();

        String closeMsg = plugin.getMinigameConfigManager().getCloseMessage(key);
        if (closeMsg != null) {
            plugin.getServer().broadcastMessage(ColorUtil.parse(
                closeMsg.replace("{portal}", portal.getName())
                        .replace("{world}",  gameWorldName)));
        }

        if (plugin.getWorldConfigManager().isItemRandomizationEnabled(gameWorldName)) {
            plugin.getItemRandomizationManager().startRandomization(gameWorldName);
        }

        int[] timeLeft = { dc.gameSeconds() };

        // Monitor last survivor + send actionbar every second.
        BukkitTask monitorTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phases.get(key) != Phase.GAME_RUNNING) return;
            World gameWorld = plugin.getServer().getWorld(gameWorldName);
            if (gameWorld == null) return;

            if (gameWorld.getPlayers().size() <= 1) {
                beginEnding(key, gameWorldName, dc.winnersWorld());
                return;
            }

            String abText = ColorUtil.parse("&eдо конца игры осталось &c" + timeLeft[0] + " &eсек.");
            for (Player p : gameWorld.getPlayers()) {
                sendActionBar(p, abText);
            }
            if (timeLeft[0] > 0) timeLeft[0]--;
        }, 20L, 20L);
        tasks.put(key + ":monitor", monitorTask);

        // Schedule normal game-over after gameSeconds.
        BukkitTask endTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (phases.get(key) == Phase.GAME_RUNNING) {
                beginEnding(key, gameWorldName, dc.winnersWorld());
            }
        }, dc.gameSeconds() * 20L);
        tasks.put(key, endTask);
    }

    /** Phase 3: announce winners to all players, wait the grace period, teleport winners, reset. */
    private void beginEnding(String key, String gameWorldName, String winnersWorldName) {
        if (phases.get(key) == Phase.GAME_ENDING) return;
        phases.put(key, Phase.GAME_ENDING);
        cancelTask(key);
        cancelTask(key + ":monitor");

        plugin.getItemRandomizationManager().stopRandomization(gameWorldName);

        // Collect winner names before teleport.
        World gameWorld = plugin.getServer().getWorld(gameWorldName);
        List<String> winnerNames = new ArrayList<>();
        if (gameWorld != null) {
            for (Player p : gameWorld.getPlayers()) {
                winnerNames.add(p.getName());
            }
        }

        // Broadcast winners to ALL players on the server.
        broadcastWinners(winnerNames);

        // Send end message to players still in the game world.
        String endMsg = plugin.getMinigameConfigManager().getEndMessage(key);
        if (endMsg != null && gameWorld != null) {
            Portal portal = plugin.getPortalManager().getPortal(key);
            String pName  = portal != null ? portal.getName() : key;
            String parsed = ColorUtil.parse(
                endMsg.replace("{portal}", pName)
                      .replace("{world}",  gameWorldName));
            for (Player p : gameWorld.getPlayers()) p.sendMessage(parsed);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            World gWorld   = plugin.getServer().getWorld(gameWorldName);
            World wWorld   = plugin.getWorldManager().loadWorld(winnersWorldName);

            if (gWorld != null && wWorld != null) {
                WorldConfigManager.WorldSpawn spawn =
                        plugin.getWorldConfigManager().getSpawn(winnersWorldName);
                Location dest = spawn != null
                        ? spawn.toLocation(wWorld)
                        : wWorld.getSpawnLocation();
                for (Player p : new ArrayList<>(gWorld.getPlayers())) {
                    p.teleportAsync(dest);
                }
            }

            if (gWorld != null && plugin.getWorldConfigManager().isCleaningEnabled(gameWorldName)) {
                plugin.getPortalSchedulerManager().cleanWorld(gWorld);
            }

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> startWaiting(key), 20L);
        }, END_GRACE_TICKS);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Broadcasts winner names to every online player. */
    private void broadcastWinners(List<String> names) {
        String header = ColorUtil.parse("&6&l━━━━━━━━━━━━━━━━━━━━━━");
        String title  = ColorUtil.parse("&e&lПобедители раунда:");
        String footer = ColorUtil.parse("&6&l━━━━━━━━━━━━━━━━━━━━━━");

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(header);
            online.sendMessage(title);
            if (names.isEmpty()) {
                online.sendMessage(ColorUtil.parse("  &7(никого не осталось)"));
            } else {
                for (String name : names) {
                    online.sendMessage(ColorUtil.parse("  &a" + name));
                }
            }
            online.sendMessage(footer);
        }
    }

    /** Sends an actionbar message (pre-parsed §-string) to a player. */
    private void sendActionBar(Player player, String legacyText) {
        player.sendActionBar(
            LegacyComponentSerializer.legacySection().deserialize(legacyText));
    }

    private void cancelTask(String key) {
        BukkitTask t = tasks.remove(key);
        if (t != null) t.cancel();
    }
}
