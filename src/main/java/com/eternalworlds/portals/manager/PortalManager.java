package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.model.Portal;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Persists portals in <plugin-data-folder>/portals.yml and keeps them in memory.
 */
public class PortalManager {

    private final EternalWorldsPlugin plugin;
    private final File portalsFile;

    private final Map<String, Portal> portals = new HashMap<>();

    public PortalManager(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
        this.portalsFile = new File(plugin.getDataFolder(), "portals.yml");
    }

    // ---- CRUD ----

    public void addPortal(Portal portal) {
        portals.put(portal.getName().toLowerCase(), portal);
        savePortals();
    }

    public boolean removePortal(String name) {
        if (portals.remove(name.toLowerCase()) != null) {
            savePortals();
            return true;
        }
        return false;
    }

    public Portal getPortal(String name) {
        return portals.get(name.toLowerCase());
    }

    public Collection<Portal> getAllPortals() {
        return Collections.unmodifiableCollection(portals.values());
    }

    /**
     * Returns the first enabled portal whose bounding box contains (world, x, y, z),
     * or null if none found.
     */
    public Portal getPortalAt(String world, int x, int y, int z) {
        for (Portal p : portals.values()) {
            if (p.isEnabled() && p.contains(world, x, y, z)) return p;
        }
        return null;
    }

    /**
     * Returns the first DISABLED portal whose bounding box contains (world, x, y, z),
     * or null if none found.  Used for spectator re-entry during dynamic-delay games.
     */
    public Portal getDisabledPortalAt(String world, int x, int y, int z) {
        for (Portal p : portals.values()) {
            if (!p.isEnabled() && p.contains(world, x, y, z)) return p;
        }
        return null;
    }

    // ---- Persistence ----

    public void loadPortals() {
        portals.clear();
        if (!portalsFile.exists()) return;

        FileConfiguration cfg = YamlConfiguration.loadConfiguration(portalsFile);
        ConfigurationSection section = cfg.getConfigurationSection("portals");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection ps = section.getConfigurationSection(key);
            if (ps == null) continue;
            try {
                Portal p = new Portal(
                        key,
                        ps.getString("source-world"),
                        ps.getInt("min-x"), ps.getInt("min-y"), ps.getInt("min-z"),
                        ps.getInt("max-x"), ps.getInt("max-y"), ps.getInt("max-z"),
                        ps.getString("dest-world"),
                        ps.getDouble("dest-x"), ps.getDouble("dest-y"), ps.getDouble("dest-z"),
                        (float) ps.getDouble("dest-yaw"), (float) ps.getDouble("dest-pitch"),
                        ps.getBoolean("enabled", true)
                );
                portals.put(key.toLowerCase(), p);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load portal: " + key, e);
            }
        }
        plugin.getLogger().info("Loaded " + portals.size() + " portal(s).");
    }

    public void savePortals() {
        FileConfiguration cfg = new YamlConfiguration();
        for (Portal p : portals.values()) {
            String path = "portals." + p.getName();
            cfg.set(path + ".source-world", p.getSourceWorld());
            cfg.set(path + ".min-x", p.getMinX());
            cfg.set(path + ".min-y", p.getMinY());
            cfg.set(path + ".min-z", p.getMinZ());
            cfg.set(path + ".max-x", p.getMaxX());
            cfg.set(path + ".max-y", p.getMaxY());
            cfg.set(path + ".max-z", p.getMaxZ());
            cfg.set(path + ".dest-world", p.getDestinationWorld());
            cfg.set(path + ".dest-x", p.getDestX());
            cfg.set(path + ".dest-y", p.getDestY());
            cfg.set(path + ".dest-z", p.getDestZ());
            cfg.set(path + ".dest-yaw", (double) p.getDestYaw());
            cfg.set(path + ".dest-pitch", (double) p.getDestPitch());
            cfg.set(path + ".enabled", p.isEnabled());
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(portalsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save portals.yml", e);
        }
    }
}
