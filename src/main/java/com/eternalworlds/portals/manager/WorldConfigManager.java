package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores per-world settings (currently: default game mode).
 * Persisted to <plugin-folder>/worlds.yml.
 */
public class WorldConfigManager {

    private final EternalWorldsPlugin plugin;
    private final File file;
    /** world name (lower-case) -> default game mode */
    private final Map<String, GameMode> worldGameModes = new HashMap<>();

    public WorldConfigManager(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
        this.file   = new File(plugin.getDataFolder(), "worlds.yml");
        load();
    }

    public void load() {
        worldGameModes.clear();
        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.isConfigurationSection("worlds")) return;

        for (String world : cfg.getConfigurationSection("worlds").getKeys(false)) {
            String gmStr = cfg.getString("worlds." + world + ".gamemode");
            if (gmStr == null) continue;
            try {
                worldGameModes.put(world.toLowerCase(), GameMode.valueOf(gmStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown gamemode '" + gmStr + "' for world '" + world + "' in worlds.yml");
            }
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, GameMode> entry : worldGameModes.entrySet()) {
            cfg.set("worlds." + entry.getKey() + ".gamemode", entry.getValue().name());
        }
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save worlds.yml: " + e.getMessage());
        }
    }

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
}
