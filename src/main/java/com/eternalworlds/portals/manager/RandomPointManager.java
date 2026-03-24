package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages per-portal random spawn points used instead of the fixed destination.
 *
 * When a player enters a portal that has random points configured:
 *   - A random unoccupied point is chosen.
 *   - If all points are occupied, the portal refuses to teleport anyone else.
 *
 * Persisted to <plugin-folder>/points.yml.
 */
public class RandomPointManager {

    /** A player is considered to occupy a point if within this squared distance (2 blocks). */
    private static final double OCCUPIED_RADIUS_SQ = 4.0;

    public record SpawnPoint(String worldName, double x, double y, double z, float yaw, float pitch) {
        /** Returns a Location, or null if the world is not loaded. */
        public Location toLocation() {
            World world = Bukkit.getWorld(worldName);
            return world != null ? new Location(world, x, y, z, yaw, pitch) : null;
        }
    }

    private final EternalWorldsPlugin plugin;
    private final File file;
    /** portal name (lower-case) -> ordered list of spawn points */
    private final Map<String, List<SpawnPoint>> portalPoints = new HashMap<>();

    public RandomPointManager(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
        this.file   = new File(plugin.getDataFolder(), "points.yml");
        load();
    }

    // ---- Persistence ----

    public void load() {
        portalPoints.clear();
        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.isConfigurationSection("portals")) return;

        for (String portal : cfg.getConfigurationSection("portals").getKeys(false)) {
            List<Map<?, ?>> raw = cfg.getMapList("portals." + portal + ".points");
            List<SpawnPoint> points = new ArrayList<>();
            for (Map<?, ?> map : raw) {
                try {
                    String world = (String) map.get("world");
                    double x     = ((Number) map.get("x")).doubleValue();
                    double y     = ((Number) map.get("y")).doubleValue();
                    double z     = ((Number) map.get("z")).doubleValue();
                    float  yaw   = ((Number) map.get("yaw")).floatValue();
                    float  pitch = ((Number) map.get("pitch")).floatValue();
                    points.add(new SpawnPoint(world, x, y, z, yaw, pitch));
                } catch (Exception ignored) {
                    plugin.getLogger().warning("Skipping malformed point entry for portal '" + portal + "'");
                }
            }
            portalPoints.put(portal.toLowerCase(), points);
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, List<SpawnPoint>> entry : portalPoints.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (SpawnPoint sp : entry.getValue()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("world", sp.worldName());
                map.put("x",     sp.x());
                map.put("y",     sp.y());
                map.put("z",     sp.z());
                map.put("yaw",   (double) sp.yaw());
                map.put("pitch", (double) sp.pitch());
                list.add(map);
            }
            cfg.set("portals." + entry.getKey() + ".points", list);
        }
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save points.yml: " + e.getMessage());
        }
    }

    // ---- API ----

    /** Appends a new spawn point for the portal at the given location. */
    public void addPoint(String portalName, Location loc) {
        String key = portalName.toLowerCase();
        portalPoints.computeIfAbsent(key, k -> new ArrayList<>())
                .add(new SpawnPoint(
                        loc.getWorld().getName(),
                        loc.getX(), loc.getY(), loc.getZ(),
                        loc.getYaw(), loc.getPitch()));
        save();
    }

    public boolean hasPoints(String portalName) {
        List<SpawnPoint> list = portalPoints.get(portalName.toLowerCase());
        return list != null && !list.isEmpty();
    }

    public int getPointCount(String portalName) {
        List<SpawnPoint> list = portalPoints.get(portalName.toLowerCase());
        return list == null ? 0 : list.size();
    }

    public void clearPoints(String portalName) {
        portalPoints.remove(portalName.toLowerCase());
        save();
    }

    /**
     * Returns a random unoccupied spawn point for the portal.
     * Returns null if all points are occupied or none are defined.
     */
    public Location getRandomAvailablePoint(String portalName) {
        List<SpawnPoint> list = portalPoints.get(portalName.toLowerCase());
        if (list == null || list.isEmpty()) return null;

        List<SpawnPoint> shuffled = new ArrayList<>(list);
        Collections.shuffle(shuffled);

        for (SpawnPoint sp : shuffled) {
            Location loc = sp.toLocation();
            if (loc == null) continue;          // world not loaded
            if (!isOccupied(loc)) return loc;
        }
        return null; // all points occupied
    }

    private boolean isOccupied(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= OCCUPIED_RADIUS_SQ) {
                return true;
            }
        }
        return false;
    }
}
