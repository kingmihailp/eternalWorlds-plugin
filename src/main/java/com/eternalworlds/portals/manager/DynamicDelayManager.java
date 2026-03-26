package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.model.Portal;
import com.eternalworlds.portals.util.ColorUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages "dynamic delay" portal configs and their lifecycle cycles.
 *
 * Config and cycle are separate concepts:
 *  - A config can exist without a running cycle (active=false).
 *  - A cycle can only run when a config exists.
 *
 * Lifecycle phases (when cycle is running):
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
    private static final String       DEF_SCOREBOARD_TITLE = "&6&lЕтернал Ворлдс";
    private static final List<String> DEF_SCOREBOARD_LINES = List.of(
            "&8───────────────",
            "&eИгроков: &c{players}"
    );

    // ── Data structures ───────────────────────────────────────────────────────

    /**
     * Persistent config stored per portal.
     * {@code active} — whether the cycle should be running (persisted).
     * Message fields are nullable — null means "use default".
     */
    public record DynamicConfig(
            int    countdownSeconds,
            int    gameSeconds,
            String winnersWorld,
            boolean active,
            // Nullable — null → use DEF_* constant
            String waitingActionbar,
            String countdownActionbar,
            String gameActionbar,
            String winnersHeader,
            String winnersTitle,
            String winnersLine,
            String winnersFooter,
            // Sidebar scoreboard — nullable → use DEF_SCOREBOARD_*
            String       scoreboardTitle,
            List<String> scoreboardLines,
            // Console commands — nullable means disabled
            String waitingCommand,       // run every waitingCommandInterval seconds during WAITING
            int    waitingCommandInterval, // seconds between waiting commands (default 5)
            String countdownCommand,     // run once when COUNTDOWN starts (2+ players detected)
            String gameStartCommand      // run once when GAME_RUNNING begins
    ) {
        /** Returns a copy with the given active flag. */
        DynamicConfig withActive(boolean active) {
            return new DynamicConfig(countdownSeconds, gameSeconds, winnersWorld, active,
                    waitingActionbar, countdownActionbar, gameActionbar,
                    winnersHeader, winnersTitle, winnersLine, winnersFooter,
                    scoreboardTitle, scoreboardLines,
                    waitingCommand, waitingCommandInterval, countdownCommand, gameStartCommand);
        }
    }

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
    /** portalName (lower-case) → current lifecycle phase (only present when cycle is running) */
    private final Map<String, Phase>         phases  = new HashMap<>();
    /**
     * portalName (lower-case) → UUIDs of players that were in the game world
     * at the moment the game started.  Used to identify spectator re-entrants.
     * Cleared when the game ends or the cycle is stopped.
     */
    private final Map<String, Set<UUID>>     gameParticipants = new HashMap<>();
    /** portalName (lower-case) → active sidebar scoreboard shown during GAME_RUNNING. */
    private final Map<String, Scoreboard>    gameScoreboards  = new HashMap<>();

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
            boolean active = cfg.getBoolean(path + ".active",        false);

            String mp = path + ".messages";
            String waitAb   = cfg.getString(mp + ".waiting-actionbar");
            String cdAb     = cfg.getString(mp + ".countdown-actionbar");
            String gameAb   = cfg.getString(mp + ".game-actionbar");
            String winHead  = cfg.getString(mp + ".winners-header");
            String winTitle = cfg.getString(mp + ".winners-title");
            String winLine  = cfg.getString(mp + ".winners-line");
            String winFoot  = cfg.getString(mp + ".winners-footer");

            String       sbTitle = cfg.getString(path + ".scoreboard.title");
            List<String> sbLines = cfg.getStringList(path + ".scoreboard.lines");

            String cp = path + ".commands";
            String waitCmd  = cfg.getString(cp + ".waiting.command");
            int    waitInt  = cfg.getInt(cp + ".waiting.interval", 5);
            String cdCmd    = cfg.getString(cp + ".countdown.command");
            String startCmd = cfg.getString(cp + ".game-start.command");

            configs.put(key.toLowerCase(), new DynamicConfig(
                    cdSec, gameSec, world, active,
                    waitAb, cdAb, gameAb,
                    winHead, winTitle, winLine, winFoot,
                    sbTitle, sbLines.isEmpty() ? null : sbLines,
                    waitCmd, waitInt, cdCmd, startCmd));
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        configs.forEach((name, dc) -> {
            String path = "portals." + name;
            cfg.set(path + ".countdown-seconds", dc.countdownSeconds());
            cfg.set(path + ".game-seconds",      dc.gameSeconds());
            cfg.set(path + ".winners-world",     dc.winnersWorld());
            cfg.set(path + ".active",            dc.active());

            String mp = path + ".messages";
            if (dc.waitingActionbar()   != null) cfg.set(mp + ".waiting-actionbar",   dc.waitingActionbar());
            if (dc.countdownActionbar() != null) cfg.set(mp + ".countdown-actionbar", dc.countdownActionbar());
            if (dc.gameActionbar()      != null) cfg.set(mp + ".game-actionbar",      dc.gameActionbar());
            if (dc.winnersHeader()      != null) cfg.set(mp + ".winners-header",      dc.winnersHeader());
            if (dc.winnersTitle()       != null) cfg.set(mp + ".winners-title",       dc.winnersTitle());
            if (dc.winnersLine()        != null) cfg.set(mp + ".winners-line",        dc.winnersLine());
            if (dc.winnersFooter()      != null) cfg.set(mp + ".winners-footer",      dc.winnersFooter());

            String sp = path + ".scoreboard";
            if (dc.scoreboardTitle() != null) cfg.set(sp + ".title", dc.scoreboardTitle());
            if (dc.scoreboardLines() != null) cfg.set(sp + ".lines", dc.scoreboardLines());

            String cp = path + ".commands";
            if (dc.waitingCommand() != null) {
                cfg.set(cp + ".waiting.command",  dc.waitingCommand());
                cfg.set(cp + ".waiting.interval", dc.waitingCommandInterval());
            }
            if (dc.countdownCommand()  != null) cfg.set(cp + ".countdown.command",  dc.countdownCommand());
            if (dc.gameStartCommand()  != null) cfg.set(cp + ".game-start.command", dc.gameStartCommand());
        });
        try {
            cfg.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[Portals] Could not save dynamic-delay.yml: " + e.getMessage());
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Saves (creates or updates) the dynamic delay config for the portal.
     * Does NOT start the cycle — call {@link #startDynamic} separately.
     * Existing message customisations and the active flag are preserved.
     */
    public void saveConfig(String portalName, int countdownSeconds, int gameSeconds, String winnersWorld) {
        String key = portalName.toLowerCase();
        DynamicConfig existing = configs.get(key);
        configs.put(key, new DynamicConfig(
                countdownSeconds, gameSeconds, winnersWorld,
                existing != null && existing.active(),   // preserve active flag
                existing != null ? existing.waitingActionbar()   : null,
                existing != null ? existing.countdownActionbar() : null,
                existing != null ? existing.gameActionbar()      : null,
                existing != null ? existing.winnersHeader()      : null,
                existing != null ? existing.winnersTitle()       : null,
                existing != null ? existing.winnersLine()        : null,
                existing != null ? existing.winnersFooter()      : null,
                existing != null ? existing.scoreboardTitle()          : null,
                existing != null ? existing.scoreboardLines()          : null,
                existing != null ? existing.waitingCommand()           : null,
                existing != null ? existing.waitingCommandInterval()   : 5,
                existing != null ? existing.countdownCommand()         : null,
                existing != null ? existing.gameStartCommand()         : null));
        save();
    }

    /**
     * Starts the dynamic cycle for the portal.
     * Config must already exist (call {@link #saveConfig} first).
     *
     * @throws IllegalStateException if no config exists for this portal.
     */
    public void startDynamic(String portalName) {
        String key = portalName.toLowerCase();
        DynamicConfig dc = configs.get(key);
        if (dc == null) throw new IllegalStateException("No config for portal: " + portalName);
        plugin.getPortalSchedulerManager().stopCycle(portalName);
        // Persist active=true
        configs.put(key, dc.withActive(true));
        save();
        startWaiting(key);
    }

    /**
     * Stops the running cycle for the portal but keeps the config.
     * Safe to call even if the cycle is not running.
     */
    public void stopDynamic(String portalName) {
        String key = portalName.toLowerCase();
        Portal stopPortal = plugin.getPortalManager().getPortal(key);
        if (stopPortal != null) removeGameScoreboard(key, stopPortal.getDestinationWorld());
        unfreezeGameWorld(key);
        cancelTask(key);
        cancelTask(key + ":monitor");
        cancelTask(key + ":actionbar");
        phases.remove(key);
        gameParticipants.remove(key);
        // Persist active=false, keep the rest of the config
        DynamicConfig dc = configs.get(key);
        if (dc != null) {
            configs.put(key, dc.withActive(false));
            save();
        }
    }

    /**
     * Removes the dynamic delay config entirely and stops the cycle.
     * After this call {@link #hasDynamic} returns false.
     */
    public void removeConfig(String portalName) {
        String key = portalName.toLowerCase();
        unfreezeGameWorld(key);
        cancelTask(key);
        cancelTask(key + ":monitor");
        cancelTask(key + ":actionbar");
        phases.remove(key);
        gameParticipants.remove(key);
        configs.remove(key);
        save();
    }

    /** Returns true if a config exists for this portal (regardless of whether the cycle is running). */
    public boolean hasDynamic(String portalName) {
        return configs.containsKey(portalName.toLowerCase());
    }

    /** Returns true if the cycle is currently running for this portal. */
    public boolean isActive(String portalName) {
        return phases.containsKey(portalName.toLowerCase());
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

    /** Returns the config for this portal, or null if none. */
    public DynamicConfig getConfig(String portalName) {
        return configs.get(portalName.toLowerCase());
    }

    /** Returns true if the portal's cycle is currently in the GAME_RUNNING phase. */
    public boolean isPhaseGameRunning(String portalName) {
        return phases.get(portalName.toLowerCase()) == Phase.GAME_RUNNING;
    }

    /**
     * Returns true if the given player UUID was recorded as a game participant
     * when the current (or most recent) game started.
     */
    public boolean isOriginalParticipant(String portalName, UUID uuid) {
        Set<UUID> set = gameParticipants.get(portalName.toLowerCase());
        return set != null && set.contains(uuid);
    }

    /** Loads persisted config and restarts all portals that had active=true. */
    public void loadAndRestart() {
        load();
        configs.forEach((key, dc) -> {
            if (dc.active()) startWaiting(key);
        });
    }

    /** Cancels all running tasks (called on plugin disable). */
    public void cancelAll() {
        plugin.getPlayerFreezeManager().unfreezeAll();
        Scoreboard main = plugin.getServer().getScoreboardManager().getMainScoreboard();
        for (Player p : plugin.getServer().getOnlinePlayers()) p.setScoreboard(main);
        gameScoreboards.clear();
        new ArrayList<>(tasks.keySet()).forEach(k -> {
            BukkitTask t = tasks.remove(k);
            if (t != null) t.cancel();
        });
        phases.clear();
        gameParticipants.clear();
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

        freezeGameWorld(key);

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

        DynamicConfig dc = configs.get(key);
        String template    = resolve(dc != null ? dc.waitingActionbar() : null, DEF_WAITING_AB);
        String abParsed    = ColorUtil.parse(template);
        String waitCmd     = dc != null ? dc.waitingCommand() : null;
        int    waitIntSec  = dc != null ? dc.waitingCommandInterval() : 5;
        int[]  cmdTick     = { 0 };

        BukkitTask abTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phases.get(key) != Phase.WAITING) return;
            Portal p = plugin.getPortalManager().getPortal(key);
            if (p == null) return;
            World destWorld = plugin.getServer().getWorld(p.getDestinationWorld());
            if (destWorld == null) return;
            for (Player player : destWorld.getPlayers()) {
                plugin.getPlayerFreezeManager().freeze(player);
                sendActionBar(player, abParsed);
            }
            if (waitCmd != null) {
                cmdTick[0]++;
                if (cmdTick[0] >= waitIntSec) {
                    cmdTick[0] = 0;
                    dispatchCommand(waitCmd, key, p.getDestinationWorld());
                }
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

        if (dc.countdownCommand() != null) {
            dispatchCommand(dc.countdownCommand(), key, gameWorldName);
        }

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
                plugin.getPlayerFreezeManager().freeze(p);
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

        unfreezeGameWorld(key);

        // Record every current player in the game world as an original participant.
        World gameWorldSnap = plugin.getServer().getWorld(gameWorldName);
        Set<UUID> participants = new HashSet<>();
        if (gameWorldSnap != null) {
            for (Player p : gameWorldSnap.getPlayers()) participants.add(p.getUniqueId());
        }
        gameParticipants.put(key, participants);

        portal.setEnabled(false);
        plugin.getPortalManager().savePortals();

        createGameScoreboard(key, gameWorldName);

        if (dc.gameStartCommand() != null) {
            dispatchCommand(dc.gameStartCommand(), key, gameWorldName);
        }

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

        BukkitTask monitorTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phases.get(key) != Phase.GAME_RUNNING) return;
            World gameWorld = plugin.getServer().getWorld(gameWorldName);
            if (gameWorld == null) return;

            long activePlayers = gameWorld.getPlayers().stream()
                    .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
                    .count();
            if (activePlayers <= 1) {
                beginEnding(key, gameWorldName, dc.winnersWorld());
                return;
            }

            updateGameScoreboard(key, gameWorldName, activePlayers);

            String abParsed = ColorUtil.parse(template.replace("{seconds}", String.valueOf(timeLeft[0])));
            for (Player p : gameWorld.getPlayers()) {
                sendActionBar(p, abParsed);
            }
            if (timeLeft[0] > 0) timeLeft[0]--;
        }, 20L, 20L);
        tasks.put(key + ":monitor", monitorTask);

        BukkitTask endTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (phases.get(key) == Phase.GAME_RUNNING) {
                beginEnding(key, gameWorldName, dc.winnersWorld());
            }
        }, dc.gameSeconds() * 20L);
        tasks.put(key, endTask);
    }

    /** Phase 3: announce winners, teleport them, clean world, re-open portal. */
    private void beginEnding(String key, String gameWorldName, String winnersWorldName) {
        if (phases.get(key) == Phase.GAME_ENDING) return;
        phases.put(key, Phase.GAME_ENDING);
        cancelTask(key);
        cancelTask(key + ":monitor");

        removeGameScoreboard(key, gameWorldName);
        plugin.getItemRandomizationManager().stopRandomization(gameWorldName);

        World gameWorld = plugin.getServer().getWorld(gameWorldName);
        List<String> winnerNames = new ArrayList<>();
        if (gameWorld != null) {
            for (Player p : gameWorld.getPlayers()) {
                // Spectators re-entered to observe — they are not winners
                if (p.getGameMode() != GameMode.SPECTATOR) winnerNames.add(p.getName());
            }
        }

        broadcastWinners(key, winnerNames);
        gameParticipants.remove(key);

        String endMsg = plugin.getMinigameConfigManager().getEndMessage(key);
        if (endMsg != null && gameWorld != null) {
            Portal portal = plugin.getPortalManager().getPortal(key);
            String pName  = portal != null ? portal.getName() : key;
            String parsed = ColorUtil.parse(
                    endMsg.replace("{portal}", pName).replace("{world}", gameWorldName));
            for (Player p : gameWorld.getPlayers()) p.sendMessage(parsed);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            World gWorld = plugin.getServer().getWorld(gameWorldName);
            World wWorld = plugin.getWorldManager().loadWorld(winnersWorldName);

            if (gWorld != null && wWorld != null) {
                WorldConfigManager.WorldSpawn spawn =
                        plugin.getWorldConfigManager().getSpawn(winnersWorldName);
                Location dest = spawn != null ? spawn.toLocation(wWorld) : wWorld.getSpawnLocation();
                GameMode winnersGm = plugin.getWorldConfigManager().getGameMode(winnersWorldName);
                for (Player p : new ArrayList<>(gWorld.getPlayers())) {
                    boolean wasSpectator = p.getGameMode() == GameMode.SPECTATOR;
                    p.teleportAsync(dest).thenAccept(ok -> {
                        if (!ok) return;
                        // Restore spectators to the winners-world game mode (or SURVIVAL).
                        if (wasSpectator) {
                            p.setGameMode(winnersGm != null ? winnersGm : GameMode.SURVIVAL);
                        }
                    });
                }
            }

            if (gWorld != null && plugin.getWorldConfigManager().isCleaningEnabled(gameWorldName)) {
                plugin.getPortalSchedulerManager().cleanWorld(gWorld);
            }

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> startWaiting(key), 20L);
        }, END_GRACE_TICKS);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void freezeGameWorld(String key) {
        Portal portal = plugin.getPortalManager().getPortal(key);
        if (portal == null) return;
        World world = plugin.getServer().getWorld(portal.getDestinationWorld());
        if (world == null) return;
        for (Player p : world.getPlayers()) plugin.getPlayerFreezeManager().freeze(p);
    }

    private void unfreezeGameWorld(String key) {
        Portal portal = plugin.getPortalManager().getPortal(key);
        if (portal == null) return;
        World world = plugin.getServer().getWorld(portal.getDestinationWorld());
        if (world == null) return;
        for (Player p : world.getPlayers()) plugin.getPlayerFreezeManager().unfreeze(p);
    }

    private void broadcastWinners(String key, List<String> names) {
        DynamicConfig dc = configs.get(key);
        String header  = ColorUtil.parse(resolve(dc != null ? dc.winnersHeader() : null, DEF_WIN_HEADER));
        String title   = ColorUtil.parse(resolve(dc != null ? dc.winnersTitle()  : null, DEF_WIN_TITLE));
        String lineTpl = resolve(dc != null ? dc.winnersLine()   : null, DEF_WIN_LINE);
        String footer  = ColorUtil.parse(resolve(dc != null ? dc.winnersFooter() : null, DEF_WIN_FOOTER));

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

    private static String resolve(String configured, String defaultVal) {
        return configured != null ? configured : defaultVal;
    }

    private void sendActionBar(Player player, String legacyText) {
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(legacyText));
    }

    /**
     * Creates the sidebar scoreboard for a running game.
     * Each configured line gets its own Team so its text can be updated dynamically.
     * Lines are displayed top-to-bottom in the order they appear in the config.
     * Supports {players} placeholder in both the title and any line.
     */
    private void createGameScoreboard(String key, String gameWorldName) {
        DynamicConfig dc = configs.get(key);
        String titleTpl = resolve(dc != null ? dc.scoreboardTitle() : null, DEF_SCOREBOARD_TITLE);
        List<String> lines = (dc != null && dc.scoreboardLines() != null)
                ? dc.scoreboardLines() : DEF_SCOREBOARD_LINES;

        Scoreboard board = plugin.getServer().getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("ewgame", Criteria.DUMMY,
                LegacyComponentSerializer.legacySection().deserialize(
                        ColorUtil.parse(titleTpl.replace("{players}", "?"))));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Each line uses a unique invisible entry string ("§0", "§1", … "§f")
        // and a Team whose prefix holds the actual display text.
        for (int i = 0; i < Math.min(lines.size(), 16); i++) {
            String entry = "§" + Integer.toHexString(i);
            Team team = board.registerNewTeam("ewline" + i);
            team.addEntry(entry);
            team.prefix(LegacyComponentSerializer.legacySection().deserialize(
                    ColorUtil.parse(lines.get(i).replace("{players}", "?"))));
            // Higher score → displayed higher; first line gets the highest score
            obj.getScore(entry).setScore(lines.size() - i);
        }

        gameScoreboards.put(key, board);
        World world = plugin.getServer().getWorld(gameWorldName);
        if (world != null) {
            for (Player p : world.getPlayers()) p.setScoreboard(board);
        }
    }

    private void updateGameScoreboard(String key, String gameWorldName, long activePlayers) {
        Scoreboard board = gameScoreboards.get(key);
        if (board == null) return;
        Objective obj = board.getObjective("ewgame");
        if (obj == null) return;

        DynamicConfig dc = configs.get(key);
        String titleTpl = resolve(dc != null ? dc.scoreboardTitle() : null, DEF_SCOREBOARD_TITLE);
        List<String> lines = (dc != null && dc.scoreboardLines() != null)
                ? dc.scoreboardLines() : DEF_SCOREBOARD_LINES;

        String count = String.valueOf(activePlayers);
        obj.displayName(LegacyComponentSerializer.legacySection().deserialize(
                ColorUtil.parse(titleTpl.replace("{players}", count))));

        for (int i = 0; i < Math.min(lines.size(), 16); i++) {
            Team team = board.getTeam("ewline" + i);
            if (team == null) continue;
            team.prefix(LegacyComponentSerializer.legacySection().deserialize(
                    ColorUtil.parse(lines.get(i).replace("{players}", count))));
        }

        // Assign board to any players who don't have it yet (e.g. spectators)
        World world = plugin.getServer().getWorld(gameWorldName);
        if (world != null) {
            for (Player p : world.getPlayers()) {
                if (!p.getScoreboard().equals(board)) p.setScoreboard(board);
            }
        }
    }

    private void removeGameScoreboard(String key, String gameWorldName) {
        Scoreboard board = gameScoreboards.remove(key);
        if (board == null) return;
        Scoreboard main = plugin.getServer().getScoreboardManager().getMainScoreboard();
        // Reset ALL online players — not just those in the game world,
        // because players may have left the world before the game ended.
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getScoreboard().equals(board)) p.setScoreboard(main);
        }
    }

    /**
     * Dispatches a console command, replacing {portal} and {world} placeholders.
     */
    private void dispatchCommand(String command, String portalKey, String gameWorldName) {
        String cmd = command
                .replace("{portal}", portalKey)
                .replace("{world}",  gameWorldName);
        try {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
        } catch (Exception e) {
            plugin.getLogger().warning("[Portals] Error dispatching command '" + cmd + "': " + e.getMessage());
        }
    }

    private void cancelTask(String key) {
        BukkitTask t = tasks.remove(key);
        if (t != null) t.cancel();
    }
}
