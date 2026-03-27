package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stores per-world settings persisted to <plugin-folder>/worlds.yml.
 *
 * YAML structure:
 * worlds:
 *   lobby:
 *     gamemode: ADVENTURE
 *     pvp: false
 *     clear-inventory: false
 *     item-randomization: false
 *     elimination:
 *       y-level: 0
 *       target-world: lobby
 *     spawn:
 *       x: 0.5
 *       y: 64.0
 *       z: 0.5
 *       yaw: 0.0
 *       pitch: 0.0
 */
public class WorldConfigManager {

    /** Immutable snapshot of a world spawn position. */
    public record WorldSpawn(double x, double y, double z, float yaw, float pitch) {
        public Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }
    }

    /** Elimination config: teleport player to targetWorld when Y <= yLevel. */
    public record EliminationConfig(int yLevel, String targetWorld) {}

    private final EternalWorldsPlugin plugin;
    private final File file;

    private final Map<String, GameMode>          worldGameModes       = new HashMap<>();
    private final Map<String, WorldSpawn>        worldSpawns          = new HashMap<>();
    private final Map<String, Boolean>           worldPvp             = new HashMap<>();
    private final Map<String, Boolean>           worldClearInventory  = new HashMap<>();
    private final Map<String, Boolean>           worldItemRandomization = new HashMap<>();
    private final Map<String, EliminationConfig> worldElimination     = new HashMap<>();
    /**
     * world name (lower-case) -> cleaning enabled.
     * When true, the world is cleaned (entities + blocks within 1000 blocks of origin)
     * at the moment winners are teleported out by the portal scheduler.
     */
    private final Map<String, Boolean>           worldCleaning        = new HashMap<>();
    /**
     * world name (lower-case) -> target world name.
     * When set, any player who leaves this world is immediately teleported to the target world.
     */
    private final Map<String, String>            worldLeavable        = new HashMap<>();
    /**
     * world name (lower-case) -> nether portal allowed.
     * When false, vanilla nether portals are blocked in that world.
     */
    private final Map<String, Boolean>           worldNetherAllowed   = new HashMap<>();
    /**
     * world name (lower-case) -> end portal allowed.
     * When false, vanilla end portals and end gateways are blocked in that world.
     */
    private final Map<String, Boolean>           worldEndAllowed      = new HashMap<>();
    /**
     * world name (lower-case) -> max Y level at which players may place blocks.
     * Null means no restriction.
     */
    private final Map<String, Integer>           worldBuildingHeight  = new HashMap<>();
    /**
     * world name (lower-case) -> bed sleeping allowed.
     * When false, players cannot sleep in a bed (spawn point is not set).
     */
    private final Map<String, Boolean>           worldBedSleeping     = new HashMap<>();
    /**
     * world name (lower-case) -> minimum Y from which block cleaning starts.
     * Blocks BELOW this Y are never removed. Null = use default (world minHeight + 5).
     */
    private final Map<String, Integer>           worldCleanMinY       = new HashMap<>();
    /**
     * world name (lower-case) -> projectiles features enabled.
     * When true: eggs, snowballs and fireballs apply knockback on hit;
     * players can throw fireballs by right-clicking with a Fire Charge.
     */
    private final Map<String, Boolean>           worldProjectilesFeatures = new HashMap<>();

    public WorldConfigManager(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
        this.file   = new File(plugin.getDataFolder(), "worlds.yml");
        load();
    }

    // ---- Persistence ----

    public void load() {
        worldGameModes.clear();
        worldSpawns.clear();
        worldPvp.clear();
        worldClearInventory.clear();
        worldItemRandomization.clear();
        worldElimination.clear();
        worldCleaning.clear();
        worldLeavable.clear();
        worldNetherAllowed.clear();
        worldEndAllowed.clear();
        worldBuildingHeight.clear();
        worldBedSleeping.clear();
        worldCleanMinY.clear();
        worldProjectilesFeatures.clear();
        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.isConfigurationSection("worlds")) return;

        for (String world : cfg.getConfigurationSection("worlds").getKeys(false)) {
            String key = world.toLowerCase();

            // Game mode
            String gmStr = cfg.getString("worlds." + world + ".gamemode");
            if (gmStr != null) {
                try {
                    worldGameModes.put(key, GameMode.valueOf(gmStr.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning(
                            "Unknown gamemode '" + gmStr + "' for world '" + world + "' in worlds.yml");
                }
            }

            // PvP
            String pvpPath = "worlds." + world + ".pvp";
            if (cfg.contains(pvpPath)) worldPvp.put(key, cfg.getBoolean(pvpPath));

            // Clear inventory
            String ciPath = "worlds." + world + ".clear-inventory";
            if (cfg.contains(ciPath)) worldClearInventory.put(key, cfg.getBoolean(ciPath));

            // Item randomization
            String irPath = "worlds." + world + ".item-randomization";
            if (cfg.contains(irPath)) worldItemRandomization.put(key, cfg.getBoolean(irPath));

            // Cleaning
            String cleanPath = "worlds." + world + ".cleaning";
            if (cfg.contains(cleanPath)) worldCleaning.put(key, cfg.getBoolean(cleanPath));

            // Elimination
            String elimPath = "worlds." + world + ".elimination";
            if (cfg.isConfigurationSection(elimPath)) {
                int    yLevel      = cfg.getInt(elimPath + ".y-level", 0);
                String targetWorld = cfg.getString(elimPath + ".target-world", "");
                if (!targetWorld.isEmpty()) {
                    worldElimination.put(key, new EliminationConfig(yLevel, targetWorld));
                }
            }

            // Leavable target
            String leavablePath = "worlds." + world + ".leavable-target";
            if (cfg.contains(leavablePath)) worldLeavable.put(key, cfg.getString(leavablePath));

            // Nether / End portal access
            String netherPath = "worlds." + world + ".nether-allowed";
            if (cfg.contains(netherPath)) worldNetherAllowed.put(key, cfg.getBoolean(netherPath));

            String endPath = "worlds." + world + ".end-allowed";
            if (cfg.contains(endPath)) worldEndAllowed.put(key, cfg.getBoolean(endPath));

            // Building height limit
            String heightPath = "worlds." + world + ".building-height";
            if (cfg.contains(heightPath)) worldBuildingHeight.put(key, cfg.getInt(heightPath));

            // Bed sleeping
            String bedPath = "worlds." + world + ".bed-sleeping";
            if (cfg.contains(bedPath)) worldBedSleeping.put(key, cfg.getBoolean(bedPath));

            // Clean min Y
            String cleanMinYPath = "worlds." + world + ".clean-min-y";
            if (cfg.contains(cleanMinYPath)) worldCleanMinY.put(key, cfg.getInt(cleanMinYPath));

            // Projectiles features
            String projPath = "worlds." + world + ".projectiles-features";
            if (cfg.contains(projPath)) worldProjectilesFeatures.put(key, cfg.getBoolean(projPath));

            // Spawn
            String spawnPath = "worlds." + world + ".spawn";
            if (cfg.isConfigurationSection(spawnPath)) {
                double x     = cfg.getDouble(spawnPath + ".x");
                double y     = cfg.getDouble(spawnPath + ".y");
                double z     = cfg.getDouble(spawnPath + ".z");
                float  yaw   = (float) cfg.getDouble(spawnPath + ".yaw");
                float  pitch = (float) cfg.getDouble(spawnPath + ".pitch");
                worldSpawns.put(key, new WorldSpawn(x, y, z, yaw, pitch));
            }
        }
    }

    public void save() {
        Set<String> worlds = new HashSet<>();
        worlds.addAll(worldGameModes.keySet());
        worlds.addAll(worldSpawns.keySet());
        worlds.addAll(worldPvp.keySet());
        worlds.addAll(worldClearInventory.keySet());
        worlds.addAll(worldItemRandomization.keySet());
        worlds.addAll(worldElimination.keySet());
        worlds.addAll(worldCleaning.keySet());
        worlds.addAll(worldLeavable.keySet());
        worlds.addAll(worldNetherAllowed.keySet());
        worlds.addAll(worldEndAllowed.keySet());
        worlds.addAll(worldBuildingHeight.keySet());
        worlds.addAll(worldBedSleeping.keySet());
        worlds.addAll(worldCleanMinY.keySet());
        worlds.addAll(worldProjectilesFeatures.keySet());

        YamlConfiguration cfg = new YamlConfiguration();
        for (String world : worlds) {
            GameMode gm = worldGameModes.get(world);
            if (gm != null) cfg.set("worlds." + world + ".gamemode", gm.name());

            Boolean pvp = worldPvp.get(world);
            if (pvp != null) cfg.set("worlds." + world + ".pvp", pvp);

            Boolean ci = worldClearInventory.get(world);
            if (ci != null) cfg.set("worlds." + world + ".clear-inventory", ci);

            Boolean ir = worldItemRandomization.get(world);
            if (ir != null) cfg.set("worlds." + world + ".item-randomization", ir);

            Boolean cleaning = worldCleaning.get(world);
            if (cleaning != null) cfg.set("worlds." + world + ".cleaning", cleaning);

            String leavable = worldLeavable.get(world);
            if (leavable != null) cfg.set("worlds." + world + ".leavable-target", leavable);

            Boolean netherAllowed = worldNetherAllowed.get(world);
            if (netherAllowed != null) cfg.set("worlds." + world + ".nether-allowed", netherAllowed);

            Boolean endAllowed = worldEndAllowed.get(world);
            if (endAllowed != null) cfg.set("worlds." + world + ".end-allowed", endAllowed);

            Integer buildingHeight = worldBuildingHeight.get(world);
            if (buildingHeight != null) cfg.set("worlds." + world + ".building-height", buildingHeight);

            Boolean bedSleeping = worldBedSleeping.get(world);
            if (bedSleeping != null) cfg.set("worlds." + world + ".bed-sleeping", bedSleeping);

            Integer cleanMinY = worldCleanMinY.get(world);
            if (cleanMinY != null) cfg.set("worlds." + world + ".clean-min-y", cleanMinY);

            Boolean projFeatures = worldProjectilesFeatures.get(world);
            if (projFeatures != null) cfg.set("worlds." + world + ".projectiles-features", projFeatures);

            EliminationConfig ec = worldElimination.get(world);
            if (ec != null) {
                cfg.set("worlds." + world + ".elimination.y-level",    ec.yLevel());
                cfg.set("worlds." + world + ".elimination.target-world", ec.targetWorld());
            }

            WorldSpawn spawn = worldSpawns.get(world);
            if (spawn != null) {
                String path = "worlds." + world + ".spawn";
                cfg.set(path + ".x",     spawn.x());
                cfg.set(path + ".y",     spawn.y());
                cfg.set(path + ".z",     spawn.z());
                cfg.set(path + ".yaw",   spawn.yaw());
                cfg.set(path + ".pitch", spawn.pitch());
            }
        }

        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save worlds.yml: " + e.getMessage());
        }
    }

    // ---- Game mode ----

    public GameMode getGameMode(String worldName) {
        return worldGameModes.get(worldName.toLowerCase());
    }

    public void setGameMode(String worldName, GameMode gameMode) {
        worldGameModes.put(worldName.toLowerCase(), gameMode);
        save();
    }

    public void removeGameMode(String worldName) {
        worldGameModes.remove(worldName.toLowerCase());
        save();
    }

    // ---- PvP ----

    public Boolean getPvp(String worldName) {
        return worldPvp.get(worldName.toLowerCase());
    }

    public void setPvp(String worldName, boolean enabled) {
        worldPvp.put(worldName.toLowerCase(), enabled);
        save();
    }

    public void removePvp(String worldName) {
        worldPvp.remove(worldName.toLowerCase());
        save();
    }

    // ---- Clear inventory on entry ----

    public boolean isClearInventory(String worldName) {
        return Boolean.TRUE.equals(worldClearInventory.get(worldName.toLowerCase()));
    }

    public void setClearInventory(String worldName, boolean clear) {
        worldClearInventory.put(worldName.toLowerCase(), clear);
        save();
    }

    // ---- Item randomization ----

    public boolean isItemRandomizationEnabled(String worldName) {
        return Boolean.TRUE.equals(worldItemRandomization.get(worldName.toLowerCase()));
    }

    public void setItemRandomizationEnabled(String worldName, boolean enabled) {
        worldItemRandomization.put(worldName.toLowerCase(), enabled);
        save();
    }

    // ---- Elimination Y level ----

    /** Returns the elimination config for this world, or null if not set. */
    public EliminationConfig getEliminationConfig(String worldName) {
        return worldElimination.get(worldName.toLowerCase());
    }

    public void setEliminationConfig(String worldName, int yLevel, String targetWorld) {
        worldElimination.put(worldName.toLowerCase(), new EliminationConfig(yLevel, targetWorld));
        save();
    }

    public void removeEliminationConfig(String worldName) {
        worldElimination.remove(worldName.toLowerCase());
        save();
    }

    // ---- World cleaning ----

    /** Returns true if the world should be cleaned when winners are teleported out. */
    public boolean isCleaningEnabled(String worldName) {
        return Boolean.TRUE.equals(worldCleaning.get(worldName.toLowerCase()));
    }

    public void setCleaningEnabled(String worldName, boolean enabled) {
        worldCleaning.put(worldName.toLowerCase(), enabled);
        save();
    }

    /** Returns the minimum Y from which block cleaning starts, or null if not configured. */
    public Integer getCleanMinY(String worldName) {
        return worldCleanMinY.get(worldName.toLowerCase());
    }

    public void setCleanMinY(String worldName, int y) {
        worldCleanMinY.put(worldName.toLowerCase(), y);
        save();
    }

    public void removeCleanMinY(String worldName) {
        worldCleanMinY.remove(worldName.toLowerCase());
        save();
    }

    // ---- Leavable (auto-teleport on world leave) ----

    /** Returns the target world players are sent to when leaving this world, or null if not set. */
    public String getLeavable(String worldName) {
        return worldLeavable.get(worldName.toLowerCase());
    }

    public void setLeavable(String worldName, String targetWorld) {
        worldLeavable.put(worldName.toLowerCase(), targetWorld);
        save();
    }

    public void removeLeavable(String worldName) {
        worldLeavable.remove(worldName.toLowerCase());
        save();
    }

    // ---- Spawn point ----

    public WorldSpawn getSpawn(String worldName) {
        return worldSpawns.get(worldName.toLowerCase());
    }

    public void setSpawn(String worldName, Location loc) {
        worldSpawns.put(worldName.toLowerCase(),
                new WorldSpawn(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
        save();
    }

    public void removeSpawn(String worldName) {
        worldSpawns.remove(worldName.toLowerCase());
        save();
    }

    // ---- Nether portal access ----

    /** Returns false if nether portals are explicitly blocked in this world, true otherwise. */
    public boolean isNetherAllowed(String worldName) {
        Boolean val = worldNetherAllowed.get(worldName.toLowerCase());
        return val == null || val; // default: allowed
    }

    public void setNetherAllowed(String worldName, boolean allowed) {
        worldNetherAllowed.put(worldName.toLowerCase(), allowed);
        save();
    }

    // ---- End portal access ----

    /** Returns false if end portals/gateways are explicitly blocked in this world, true otherwise. */
    public boolean isEndAllowed(String worldName) {
        Boolean val = worldEndAllowed.get(worldName.toLowerCase());
        return val == null || val; // default: allowed
    }

    public void setEndAllowed(String worldName, boolean allowed) {
        worldEndAllowed.put(worldName.toLowerCase(), allowed);
        save();
    }

    // ---- Building height limit ----

    /** Returns the max Y at which players may place blocks, or null if unrestricted. */
    public Integer getBuildingHeight(String worldName) {
        return worldBuildingHeight.get(worldName.toLowerCase());
    }

    public void setBuildingHeight(String worldName, int maxY) {
        worldBuildingHeight.put(worldName.toLowerCase(), maxY);
        save();
    }

    public void removeBuildingHeight(String worldName) {
        worldBuildingHeight.remove(worldName.toLowerCase());
        save();
    }

    // ---- Bed sleeping ----

    /** Returns false if bed sleeping is explicitly disabled in this world, true otherwise. */
    public boolean isBedSleepingAllowed(String worldName) {
        Boolean val = worldBedSleeping.get(worldName.toLowerCase());
        return val == null || val; // default: allowed
    }

    public void setBedSleepingAllowed(String worldName, boolean allowed) {
        worldBedSleeping.put(worldName.toLowerCase(), allowed);
        save();
    }

    /** Returns true if projectiles features (knockback + fireball throw) are enabled in this world. */
    public boolean isProjectilesFeaturesEnabled(String worldName) {
        return Boolean.TRUE.equals(worldProjectilesFeatures.get(worldName.toLowerCase()));
    }

    public void setProjectilesFeaturesEnabled(String worldName, boolean enabled) {
        worldProjectilesFeatures.put(worldName.toLowerCase(), enabled);
        save();
    }
}
