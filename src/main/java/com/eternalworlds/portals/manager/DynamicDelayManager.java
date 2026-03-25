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
 * Lifecycle:
 *  WAITING      – portal open; ≤1 player in game world.
 *                 Players are frozen. ActionBar: waiting-actionbar.
 *  COUNTDOWN    – 2+ players detected; countdown to game start.
 *                 Players remain frozen. ActionBar: countdown-actionbar.
 *  GAME_RUNNING – portal closed; game in progress.
 *                 Players unfrozen. ActionBar: game-actionbar.
 *  GAME_ENDING  – last survivor or timer expired; winners announced,
 *                 survivors teleported, world cleaned, portal re-opens.
 *
 * All message templates are configurable per-portal in dynamic-delay.yml
 * and support hex colors (&#RRGGBB / #RRGGBB) and standard &-codes.
 */
public class DynamicDelayManager {

    /** How often to poll the online player count while waiting (ticks). */
    private static final long POLL_TICKS      = 40L;  // 2 s
    /** Grace period after the end-message before teleporting winners (ticks). */
    private static final long END_GRACE_TICKS = 60L;  // 3 s

    // ── Default message templates ─────────────────────────────────────────────

    private static final String DEF_WAITING_AB   = "&#FF6B6B&oожидание игроков…";
    private static final String DEF_COUNTDOWN_AB = "&eдо начала игры осталось &c{seconds} &eсек.";
    private static final String DEF_GAME_AB      = "&eдо конца игры осталось &c{seconds} &eсек.";
    private static final String DEF_WIN_HEADER   = "&6&l━━━━━━━━━━━━━━━━━━━━━━";
    private static final String DEF_WIN_TITLE    = "&e&lПобедители раунда:";
    private static final String DEF_WIN_LINE     = "  &a{player}";
    private static final String DEF_WIN_FOOTER   = "&6&l━━━━━━━━━━━━━━━━━━━━━━";

    // ── Data structures ───────────────────────────────────────────────────────

    /**
     * Persistent config stored per portal.
     * Message fields are nullable — null means "use default".
     */
    public record DynamicConfig(
            int    countdownSeconds,
            int    gameSeconds,
            String winnersWorld,
            // Nullable — null → use DEF_* constant
            String waitingActionbar,
            String countdownActionbar,
            String gameActionbar,
            String winnersHeader,
            String winnersTitle,
            String winnersLine,
            String winnersFooter
    ) {}

    private enum Phase { WAITING, COUNTDOWN, GAME_RUNNING, GAME_ENDING }

    private final EternalWorldsPlugin plugin;
    private final File                dataFile;

    /** portalName (lower-case) → persisted config */
    private final Map<String, DynamicConfig> configs = new HashMap<>();
    /**
     * Active Bukkit tasks, keyed by:
     *   "<portal>"           – main lifecycle task (poll / countdown / endTimer)
     *   "<portal>:monitor"   – game monitor + actionbar task
     *   "<portal>:actionbar" – waiting-phase actionbar + freeze task
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

            String mp = path + ".messages";
            String waitAb   = cfg.getString(mp + ".waiting-actionbar");
            String cdAb     = cfg.getString(mp + ".countdown-actionbar");
            String gameAb   = cfg.getString(mp + ".game-actionbar");
            String winHead  = cfg.getString(mp + ".winners-header");
            String winTitle = cfg.getString(mp + ".winners-title");
            String winLine  = cfg.getString(mp + ".winners-line");
            String winFoot  = cfg.getString(mp + ".winners-footer");

            configs.put(key.toLowerCase(), new DynamicConfig(
                    cdSec, gameSec, world,
                    waitAb, cdAb, gameAb,
                    winHead, winTitle, winLine, winFoot));
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        configs.forEach((name, dc) -> {
            String path = "portals." + name;
            cfg.set(path + ".countdown-seconds", dc.countdownSeconds());
            cfg.set(path + ".game-seconds",      dc.gameSeconds());
            cfg.set(path + ".winners-world",     dc.winnersWorld());

            String mp = path + ".messages";
            if (dc.waitingActionbar()   != null) cfg.set(mp + ".waiting-actionbar",   dc.waitingActionbar());
            if (dc.countdownActionbar() != null) cfg.set(mp + ".countdown-actionbar", dc.countdownActionbar());
            if (dc.gameActionbar()      != null) cfg.set(mp + ".game-actionbar",      dc.gameActionbar());
            if (dc.winnersHeader()      != null) cfg.set(mp + ".winners-header",      dc.winnersHeader());
            if (dc.winnersTitle()       != null) cfg.set(mp + ".winners-title",       dc.winnersTitle());
            if (dc.winnersLine()        != null) cfg.set(mp + ".winners-line",        dc.winnersLine());
            if (dc.winnersFooter()      != null) cfg.set(mp + ".winners-footer",      dc.winnersFooter());
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
     * Preserves any existing message configuration for the portal.
     */
    public void startDynamic(String portalName, int countdownSeconds, int gameSeconds, String winnersWorld) {
        String key = portalName.toLowerCase();
        DynamicConfig existing = configs.get(key);
        configs.put(key, new DynamicConfig(
                countdownSeconds, gameSeconds, winnersWorld,
                existing != null ? existing.waitingActionbar()   : null,
                existing != null ? existing.countdownActionbar() : null,
                existing != null ? existing.gameActionbar()      : null,
                existing != null ? existing.winnersHeader()      : null,
                existing != null ? existing.winnersTitle()       : null,
                existing != null ? existing.winnersLine()        : null,
                existing != null ? existing.winnersFooter()      : null));
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
        unfreezeGameWorld(key);
        configs.remove(key);
        cancelTask(key);
        cancelTask(key + ":monitor");
        cancelTask(key + ":actionbar");
        phases.remove(key);
        save();
    }

    /**
     * Returns true if the given world name is currently in WAITING or COUNTDOWN
     * phase for any portal.  Used by PortalListener to unfreeze players on world leave.
     */
    public boolean isWorldFreezeActive(String worldName) {
        for (Map.Entry<String, Phase> entry : phases.entrySet()) {
            Phase ph = entry.getValue();
            if (ph != Phase.WAITING && ph != Phase.COUNTDOWN) continue;
            Portal p = plugin.getPortalManager().getPortal(entry.getKey());
            if (p != null && worldName.equalsIgnoreCase(p.getDestinationWorld())) return true;
        }
        return false;
    }

    /** Loads persisted config and restarts all dynamic portals (called on plugin enable). */
    public void loadAndRestart() {
        load();
        configs.keySet().forEach(this::startWaiting);
    }

    /** Cancels all running tasks (called on plugin disable). */
    public void cancelAll() {
        plugin.getPlayerFreezeManager().unfreezeAll();
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

        // Freeze any players already in the game world.
        freezeGameWorld(key);

        // Poll player count every 2 s.
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

        // Actionbar + re-freeze any new players every second.
        String template = resolve(configs.get(key) != null ? configs.get(key).waitingActionbar() : null, DEF_WAITING_AB);
        String abParsed = ColorUtil.parse(template);
        BukkitTask abTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phases.get(key) != Phase.WAITING) return;
            Portal p = plugin.getPortalManager().getPortal(key);
            if (p == null) return;
            World destWorld = plugin.getServer().getWorld(p.getDestinationWorld());
            if (destWorld == null) return;
            for (Player player : destWorld.getPlayers()) {
                plugin.getPlayerFreezeManager().freeze(player); // idempotent
                sendActionBar(player, abParsed);
            }
        }, 20L, 20L);
        tasks.put(key + ":actionbar", abTask);
    }

    /** Phase 1b: countdown before the game starts. */
    private void startCountdown(String key) {
        cancelTask(key);
        cancelTask(key + ":actionbar");
        phases.put(key, Phase.COUNTDOWN);

        DynamicConfig dc = configs.get(key);
        if (dc == null) { startWaiting(key); return; }

        Portal portal = plugin.getPortalManager().getPortal(key);
        if (portal == null) { startWaiting(key); return; }
        String gameWorldName = portal.getDestinationWorld();

        String template = resolve(dc.countdownActionbar(), DEF_COUNTDOWN_AB);
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

            String abParsed = ColorUtil.parse(template.replace("{seconds}", String.valueOf(remaining[0])));
            for (Player p : destWorld.getPlayers()) {
                plugin.getPlayerFreezeManager().freeze(p); // freeze latecomers
                sendActionBar(p, abParsed);
            }
            remaining[0]--;
        }, 20L, 20L);
        tasks.put(key, cdTask);
    }

    /** Phase 2: close the portal, start the game timer, unfreeze players. */
    private void beginGame(String key) {
        DynamicConfig dc = configs.get(key);
        if (dc == null) return;
        cancelTask(key);
        phases.put(key, Phase.GAME_RUNNING);

        Portal portal = plugin.getPortalManager().getPortal(key);
        if (portal == null) { startWaiting(key); return; }
        String gameWorldName = portal.getDestinationWorld();

        // Unfreeze all players in the game world.
        unfreezeGameWorld(key);

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

        String template = resolve(dc.gameActionbar(), DEF_GAME_AB);
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

            String abParsed = ColorUtil.parse(template.replace("{seconds}", String.valueOf(timeLeft[0])));
            for (Player p : gameWorld.getPlayers()) {
                sendActionBar(p, abParsed);
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

    /** Phase 3: announce winners to all players, teleport them, clean world, re-open portal. */
    private void beginEnding(String key, String gameWorldName, String winnersWorldName) {
        if (phases.get(key) == Phase.GAME_ENDING) return;
        phases.put(key, Phase.GAME_ENDING);
        cancelTask(key);
        cancelTask(key + ":monitor");

        plugin.getItemRandomizationManager().stopRandomization(gameWorldName);

        // Collect winner names BEFORE teleport.
        World gameWorld = plugin.getServer().getWorld(gameWorldName);
        List<String> winnerNames = new ArrayList<>();
        if (gameWorld != null) {
            for (Player p : gameWorld.getPlayers()) {
                winnerNames.add(p.getName());
            }
        }

        // Broadcast winners to ALL online players.
        broadcastWinners(key, winnerNames);

        // Send end message to remaining game-world players.
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
            World gWorld = plugin.getServer().getWorld(gameWorldName);
            World wWorld = plugin.getWorldManager().loadWorld(winnersWorldName);

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

    /** Freezes all players currently in the portal's game world. */
    private void freezeGameWorld(String key) {
        Portal portal = plugin.getPortalManager().getPortal(key);
        if (portal == null) return;
        World world = plugin.getServer().getWorld(portal.getDestinationWorld());
        if (world == null) return;
        PlayerFreezeManager fm = plugin.getPlayerFreezeManager();
        for (Player p : world.getPlayers()) fm.freeze(p);
    }

    /** Unfreezes all players currently in the portal's game world. */
    private void unfreezeGameWorld(String key) {
        Portal portal = plugin.getPortalManager().getPortal(key);
        if (portal == null) return;
        World world = plugin.getServer().getWorld(portal.getDestinationWorld());
        if (world == null) return;
        PlayerFreezeManager fm = plugin.getPlayerFreezeManager();
        for (Player p : world.getPlayers()) fm.unfreeze(p);
    }

    /** Broadcasts the winner announcement to every online player. */
    private void broadcastWinners(String key, List<String> names) {
        DynamicConfig dc = configs.get(key);
        String header = ColorUtil.parse(resolve(dc != null ? dc.winnersHeader() : null, DEF_WIN_HEADER));
        String title  = ColorUtil.parse(resolve(dc != null ? dc.winnersTitle()  : null, DEF_WIN_TITLE));
        String lineTpl = resolve(dc != null ? dc.winnersLine() : null, DEF_WIN_LINE);
        String footer = ColorUtil.parse(resolve(dc != null ? dc.winnersFooter() : null, DEF_WIN_FOOTER));

        for (Player online : plugin.getServer().getOnlinePlayers()) {
            online.sendMessage(header);
            online.sendMessage(title);
            if (names.isEmpty()) {
                online.sendMessage(ColorUtil.parse("  &7(никого не осталось)"));
            } else {
                for (String name : names) {
                    online.sendMessage(ColorUtil.parse(lineTpl.replace("{player}", name)));
                }
            }
            online.sendMessage(footer);
        }
    }

    /** Returns {@code configured} if non-null, otherwise {@code defaultVal}. */
    private static String resolve(String configured, String defaultVal) {
        return configured != null ? configured : defaultVal;
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
