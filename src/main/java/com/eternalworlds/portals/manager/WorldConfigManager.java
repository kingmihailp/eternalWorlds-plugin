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

            // Elimination
            String elimPath = "worlds." + world + ".elimination";
            if (cfg.isConfigurationSection(elimPath)) {
                int    yLevel      = cfg.getInt(elimPath + ".y-level", 0);
                String targetWorld = cfg.getString(elimPath + ".target-world", "");
                if (!targetWorld.isEmpty()) {
                    worldElimination.put(key, new EliminationConfig(yLevel, targetWorld));
                }
            }

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
}
