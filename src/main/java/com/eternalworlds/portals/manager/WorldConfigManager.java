package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores per-world settings: default game mode and custom spawn point.
 * Persisted to <plugin-folder>/worlds.yml.
 *
 * YAML structure:
 * worlds:
 *   lobby:
 *     gamemode: ADVENTURE
 *     pvp: false
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

    private final EternalWorldsPlugin plugin;
    private final File file;

    /** world name (lower-case) -> default game mode */
    private final Map<String, GameMode>   worldGameModes = new HashMap<>();
    /** world name (lower-case) -> custom spawn point */
    private final Map<String, WorldSpawn> worldSpawns    = new HashMap<>();
    /**
     * world name (lower-case) -> pvp override.
     * true = PvP on, false = PvP off, absent = use world's own setting.
     */
    private final Map<String, Boolean>    worldPvp            = new HashMap<>();
    /**
     * world name (lower-case) -> clear inventory on entry.
     * true = clear inventory when a player enters this world, absent/false = keep inventory.
     */
    private final Map<String, Boolean>    worldClearInventory = new HashMap<>();

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
            if (cfg.contains(pvpPath)) {
                worldPvp.put(key, cfg.getBoolean(pvpPath));
            }

            // Clear inventory on entry
            String clearInvPath = "worlds." + world + ".clear-inventory";
            if (cfg.contains(clearInvPath)) {
                worldClearInventory.put(key, cfg.getBoolean(clearInvPath));
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
        java.util.Set<String> worlds = new java.util.HashSet<>();
        worlds.addAll(worldGameModes.keySet());
        worlds.addAll(worldSpawns.keySet());
        worlds.addAll(worldPvp.keySet());
        worlds.addAll(worldClearInventory.keySet());

        YamlConfiguration cfg = new YamlConfiguration();
        for (String world : worlds) {
            GameMode gm = worldGameModes.get(world);
            if (gm != null) {
                cfg.set("worlds." + world + ".gamemode", gm.name());
            }
            Boolean pvp = worldPvp.get(world);
            if (pvp != null) {
                cfg.set("worlds." + world + ".pvp", pvp);
            }
            Boolean clearInv = worldClearInventory.get(world);
            if (clearInv != null) {
                cfg.set("worlds." + world + ".clear-inventory", clearInv);
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

    /** Returns the default GameMode for the given world, or null if not set. */
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

    // ---- Clear inventory on entry ----

    /** Returns true if inventory should be cleared when a player enters this world. */
    public boolean isClearInventory(String worldName) {
        return Boolean.TRUE.equals(worldClearInventory.get(worldName.toLowerCase()));
    }

    public void setClearInventory(String worldName, boolean clear) {
        worldClearInventory.put(worldName.toLowerCase(), clear);
        save();
    }

    public void removeClearInventory(String worldName) {
        worldClearInventory.remove(worldName.toLowerCase());
        save();
    }

    // ---- PvP ----

    /**
     * Returns the PvP setting for the world: true = on, false = off, null = not overridden.
     */
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

    // ---- Spawn point ----

    /** Returns the custom spawn for the given world, or null if not set. */
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
