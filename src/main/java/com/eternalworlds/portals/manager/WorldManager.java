package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles loading and listing worlds from the server's world container folder.
 */
public class WorldManager {

    private final EternalWorldsPlugin plugin;

    public WorldManager(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Loads a world by name. If it is already loaded, the existing instance is returned.
     * If the world folder exists in the world container, that data is used; otherwise
     * a new default world is generated.
     *
     * @return the loaded World, or null if creation failed
     */
    public World loadWorld(String name) {
        World existing = Bukkit.getWorld(name);
        if (existing != null) return existing;

        World created = new WorldCreator(name).createWorld();
        if (created == null) {
            plugin.getLogger().warning("Failed to load/create world: " + name);
        }
        return created;
    }

    /**
     * Returns names of world folders found in the world container that are
     * NOT currently loaded.
     */
    public List<String> listUnloadedWorlds() {
        File container = Bukkit.getServer().getWorldContainer();
        List<String> loaded = Bukkit.getWorlds().stream().map(World::getName).toList();

        List<String> result = new ArrayList<>();
        File[] dirs = container.listFiles(File::isDirectory);
        if (dirs == null) return result;

        for (File dir : dirs) {
            if (new File(dir, "level.dat").exists() && !loaded.contains(dir.getName())) {
                result.add(dir.getName());
            }
        }
        return result;
    }

    /**
     * Returns names of all worlds currently loaded on the server.
     */
    public List<String> listLoadedWorlds() {
        return Bukkit.getWorlds().stream().map(World::getName).toList();
    }
}
