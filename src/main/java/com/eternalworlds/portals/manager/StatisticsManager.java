package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.util.ColorUtil;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Tracks per-player statistics (kills, deaths, wins, blocks placed, damage dealt)
 * across named counters, each scoped to a specific world.
 *
 * Counter configs are stored in statistics.yml.
 * Player data is stored in statistics-data.yml.
 *
 * Kills/deaths/wins are saved immediately.
 * Blocks-placed/damage-dealt are marked dirty and flushed every 5 minutes + on disable.
 */
public class StatisticsManager {

    // ── Default message templates ─────────────────────────────────────────────

    private static final String DEF_HEADER  = "&6&l━━ {name} ━━";
    private static final String DEF_KILLS   = "&eУбийства: &c{value}";
    private static final String DEF_DEATHS  = "&eСмерти: &c{value}";
    private static final String DEF_WINS    = "&eПобеды: &c{value}";
    private static final String DEF_BLOCKS  = "&eБлоков поставлено: &c{value}";
    private static final String DEF_DAMAGE  = "&eУрона нанесено: &c{value}";
    private static final String DEF_FOOTER  = "&6&l━━━━━━━━━━━━━━━━━━━━━━";

    // ── Data structures ───────────────────────────────────────────────────────

    public record StatCounter(
            String  name,
            String  world,
            boolean trackKills,
            boolean trackDeaths,
            boolean trackWins,
            boolean trackBlocksPlaced,
            boolean trackDamageDealt,
            // Nullable message templates — null → use DEF_* constant
            String  msgHeader,
            String  msgKills,
            String  msgDeaths,
            String  msgWins,
            String  msgBlocksPlaced,
            String  msgDamageDealt,
            String  msgFooter
    ) {
        /** Returns a copy with the given tracking flag flipped. */
        public StatCounter withTracking(String metric, boolean enabled) {
            return new StatCounter(name, world,
                    metric.equals("kills")         ? enabled : trackKills,
                    metric.equals("deaths")        ? enabled : trackDeaths,
                    metric.equals("wins")          ? enabled : trackWins,
                    metric.equals("blocks-placed") ? enabled : trackBlocksPlaced,
                    metric.equals("damage-dealt")  ? enabled : trackDamageDealt,
                    msgHeader, msgKills, msgDeaths, msgWins, msgBlocksPlaced, msgDamageDealt, msgFooter);
        }
    }

    public static class PlayerStats {
        public int    kills;
        public int    deaths;
        public int    wins;
        public int    blocksPlaced;
        public double damageDealt;

        public PlayerStats(int kills, int deaths, int wins, int blocksPlaced, double damageDealt) {
            this.kills        = kills;
            this.deaths       = deaths;
            this.wins         = wins;
            this.blocksPlaced = blocksPlaced;
            this.damageDealt  = damageDealt;
        }
    }

    private final EternalWorldsPlugin plugin;
    private final File                configFile;
    private final File                dataFile;

    /** counterName (lower-case) → config */
    private final Map<String, StatCounter>              counters = new LinkedHashMap<>();
    /** counterName (lower-case) → (UUID → stats) */
    private final Map<String, Map<UUID, PlayerStats>>   data     = new HashMap<>();

    private boolean dataDirty = false;

    public StatisticsManager(EternalWorldsPlugin plugin) {
        this.plugin     = plugin;
        this.configFile = new File(plugin.getDataFolder(), "statistics.yml");
        this.dataFile   = new File(plugin.getDataFolder(), "statistics-data.yml");
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void load() {
        counters.clear();
        data.clear();

        if (configFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
            if (cfg.isConfigurationSection("counters")) {
                for (String name : cfg.getConfigurationSection("counters").getKeys(false)) {
                    String  path   = "counters." + name;
                    String  world  = cfg.getString(path + ".world", "world");
                    boolean kills  = cfg.getBoolean(path + ".track-kills",         true);
                    boolean deaths = cfg.getBoolean(path + ".track-deaths",        true);
                    boolean wins   = cfg.getBoolean(path + ".track-wins",          true);
                    boolean blocks = cfg.getBoolean(path + ".track-blocks-placed", true);
                    boolean damage = cfg.getBoolean(path + ".track-damage-dealt",  true);

                    String mp = path + ".messages";
                    counters.put(name.toLowerCase(), new StatCounter(
                            name, world.toLowerCase(), kills, deaths, wins, blocks, damage,
                            cfg.getString(mp + ".header"),
                            cfg.getString(mp + ".kills"),
                            cfg.getString(mp + ".deaths"),
                            cfg.getString(mp + ".wins"),
                            cfg.getString(mp + ".blocks-placed"),
                            cfg.getString(mp + ".damage-dealt"),
                            cfg.getString(mp + ".footer")));
                }
            }
        }

        if (dataFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
            if (cfg.isConfigurationSection("data")) {
                for (String counterName : cfg.getConfigurationSection("data").getKeys(false)) {
                    String path = "data." + counterName;
                    if (!cfg.isConfigurationSection(path)) continue;
                    Map<UUID, PlayerStats> playerMap = new HashMap<>();
                    for (String uuidStr : cfg.getConfigurationSection(path).getKeys(false)) {
                        try {
                            UUID   uuid  = UUID.fromString(uuidStr);
                            String pPath = path + "." + uuidStr;
                            playerMap.put(uuid, new PlayerStats(
                                    cfg.getInt(pPath    + ".kills",         0),
                                    cfg.getInt(pPath    + ".deaths",        0),
                                    cfg.getInt(pPath    + ".wins",          0),
                                    cfg.getInt(pPath    + ".blocks-placed", 0),
                                    cfg.getDouble(pPath + ".damage-dealt",  0)));
                        } catch (IllegalArgumentException ignored) {}
                    }
                    data.put(counterName.toLowerCase(), playerMap);
                }
            }
        }
    }

    private void saveConfig() {
        YamlConfiguration cfg = new YamlConfiguration();
        counters.forEach((key, sc) -> {
            String path = "counters." + key;
            cfg.set(path + ".world",               sc.world());
            cfg.set(path + ".track-kills",         sc.trackKills());
            cfg.set(path + ".track-deaths",        sc.trackDeaths());
            cfg.set(path + ".track-wins",          sc.trackWins());
            cfg.set(path + ".track-blocks-placed", sc.trackBlocksPlaced());
            cfg.set(path + ".track-damage-dealt",  sc.trackDamageDealt());

            String mp = path + ".messages";
            if (sc.msgHeader()       != null) cfg.set(mp + ".header",        sc.msgHeader());
            if (sc.msgKills()        != null) cfg.set(mp + ".kills",         sc.msgKills());
            if (sc.msgDeaths()       != null) cfg.set(mp + ".deaths",        sc.msgDeaths());
            if (sc.msgWins()         != null) cfg.set(mp + ".wins",          sc.msgWins());
            if (sc.msgBlocksPlaced() != null) cfg.set(mp + ".blocks-placed", sc.msgBlocksPlaced());
            if (sc.msgDamageDealt()  != null) cfg.set(mp + ".damage-dealt",  sc.msgDamageDealt());
            if (sc.msgFooter()       != null) cfg.set(mp + ".footer",        sc.msgFooter());
        });
        trySave(cfg, configFile, "statistics.yml");
    }

    public void saveData() {
        YamlConfiguration cfg = new YamlConfiguration();
        data.forEach((counterKey, playerMap) ->
                playerMap.forEach((uuid, stats) -> {
                    String path = "data." + counterKey + "." + uuid;
                    cfg.set(path + ".kills",         stats.kills);
                    cfg.set(path + ".deaths",        stats.deaths);
                    cfg.set(path + ".wins",          stats.wins);
                    cfg.set(path + ".blocks-placed", stats.blocksPlaced);
                    cfg.set(path + ".damage-dealt",  stats.damageDealt);
                }));
        trySave(cfg, dataFile, "statistics-data.yml");
        dataDirty = false;
    }

    /** Flush unsaved data if dirty (called periodically and on plugin disable). */
    public void flushIfDirty() {
        if (dataDirty) saveData();
    }

    private void trySave(YamlConfiguration cfg, File file, String name) {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("[Portals] Could not save " + name + ": " + e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void createCounter(String name, String world) {
        counters.put(name.toLowerCase(), new StatCounter(
                name, world.toLowerCase(),
                true, true, true, true, true,
                null, null, null, null, null, null, null));
        saveConfig();
    }

    /** Returns false if the counter didn't exist. */
    public boolean removeCounter(String name) {
        String key = name.toLowerCase();
        if (!counters.containsKey(key)) return false;
        counters.remove(key);
        data.remove(key);
        saveConfig();
        saveData();
        return true;
    }

    /**
     * Toggles a tracking metric for the given counter.
     * Valid metric values: kills, deaths, wins, blocks-placed, damage-dealt
     * Returns false if counter not found.
     */
    public boolean setTracking(String name, String metric, boolean enabled) {
        String      key = name.toLowerCase();
        StatCounter sc  = counters.get(key);
        if (sc == null) return false;
        counters.put(key, sc.withTracking(metric, enabled));
        saveConfig();
        return true;
    }

    public StatCounter getCounter(String name) {
        return counters.get(name.toLowerCase());
    }

    public Collection<StatCounter> getAllCounters() {
        return counters.values();
    }

    /** Returns all counters that track the given world. */
    public List<StatCounter> getCountersForWorld(String worldName) {
        List<StatCounter> result = new ArrayList<>();
        for (StatCounter sc : counters.values()) {
            if (sc.world().equalsIgnoreCase(worldName)) result.add(sc);
        }
        return result;
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    public void recordKill(String world, UUID killer) {
        for (StatCounter sc : getCountersForWorld(world)) {
            if (sc.trackKills()) getOrCreate(sc.name().toLowerCase(), killer).kills++;
        }
        saveData();
    }

    public void recordDeath(String world, UUID victim) {
        for (StatCounter sc : getCountersForWorld(world)) {
            if (sc.trackDeaths()) getOrCreate(sc.name().toLowerCase(), victim).deaths++;
        }
        saveData();
    }

    public void recordWin(String world, UUID winner) {
        for (StatCounter sc : getCountersForWorld(world)) {
            if (sc.trackWins()) getOrCreate(sc.name().toLowerCase(), winner).wins++;
        }
        saveData();
    }

    /** High-frequency — marks dirty only; flushed on periodic task / disable. */
    public void recordBlockPlaced(String world, UUID player) {
        for (StatCounter sc : getCountersForWorld(world)) {
            if (sc.trackBlocksPlaced()) getOrCreate(sc.name().toLowerCase(), player).blocksPlaced++;
        }
        dataDirty = true;
    }

    /** High-frequency — marks dirty only; flushed on periodic task / disable. */
    public void recordDamageDealt(String world, UUID attacker, double damage) {
        for (StatCounter sc : getCountersForWorld(world)) {
            if (sc.trackDamageDealt()) getOrCreate(sc.name().toLowerCase(), attacker).damageDealt += damage;
        }
        dataDirty = true;
    }

    private PlayerStats getOrCreate(String counterKey, UUID uuid) {
        return data.computeIfAbsent(counterKey, k -> new HashMap<>())
                   .computeIfAbsent(uuid, k -> new PlayerStats(0, 0, 0, 0, 0.0));
    }

    // ── Display ───────────────────────────────────────────────────────────────

    public PlayerStats getPlayerStats(String counterName, UUID uuid) {
        Map<UUID, PlayerStats> pm = data.get(counterName.toLowerCase());
        return pm != null ? pm.get(uuid) : null;
    }

    /**
     * Formats the statistics for a player as a list of chat-ready lines.
     * Returns null if the counter doesn't exist.
     */
    public List<String> formatStats(String counterName, UUID uuid) {
        StatCounter sc = counters.get(counterName.toLowerCase());
        if (sc == null) return null;

        PlayerStats stats = getPlayerStats(counterName, uuid);
        if (stats == null) stats = new PlayerStats(0, 0, 0, 0, 0.0);

        List<String> lines = new ArrayList<>();
        lines.add(ColorUtil.parse(resolve(sc.msgHeader(), DEF_HEADER).replace("{name}", sc.name())));

        if (sc.trackKills())
            lines.add(ColorUtil.parse(resolve(sc.msgKills(), DEF_KILLS)
                    .replace("{value}", String.valueOf(stats.kills))));
        if (sc.trackDeaths())
            lines.add(ColorUtil.parse(resolve(sc.msgDeaths(), DEF_DEATHS)
                    .replace("{value}", String.valueOf(stats.deaths))));
        if (sc.trackWins())
            lines.add(ColorUtil.parse(resolve(sc.msgWins(), DEF_WINS)
                    .replace("{value}", String.valueOf(stats.wins))));
        if (sc.trackBlocksPlaced())
            lines.add(ColorUtil.parse(resolve(sc.msgBlocksPlaced(), DEF_BLOCKS)
                    .replace("{value}", String.valueOf(stats.blocksPlaced))));
        if (sc.trackDamageDealt())
            lines.add(ColorUtil.parse(resolve(sc.msgDamageDealt(), DEF_DAMAGE)
                    .replace("{value}", String.format("%.1f", stats.damageDealt))));

        lines.add(ColorUtil.parse(resolve(sc.msgFooter(), DEF_FOOTER).replace("{name}", sc.name())));
        return lines;
    }

    private static String resolve(String configured, String defaultVal) {
        return configured != null ? configured : defaultVal;
    }
}
