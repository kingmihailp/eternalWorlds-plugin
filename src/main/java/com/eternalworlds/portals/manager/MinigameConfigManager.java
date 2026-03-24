package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-portal minigame settings, persisted to <plugin-folder>/minigame.yml.
 *
 * YAML structure:
 * portals:
 *   myPortal:
 *     winners-dest: lobby
 *     open-message:  "&#00FF00Portal {portal} has &aopened&r&#00FF00!"
 *     close-message: "&#FF5500Portal {portal} has &cclosed&r&#FF5500. Game on!"
 *     end-message:   "&#FFD700Game over! Teleporting to results in 3 seconds..."
 *
 * Placeholders supported in messages: {portal}, {world}
 */
public class MinigameConfigManager {

    private final EternalWorldsPlugin plugin;
    private final File file;

    /** portal name (lower-case) -> winners destination world */
    private final Map<String, String> winnersDestMap  = new HashMap<>();
    /** portal name (lower-case) -> open message (broadcast when portal enables) */
    private final Map<String, String> openMessageMap  = new HashMap<>();
    /** portal name (lower-case) -> close message (broadcast when portal disables) */
    private final Map<String, String> closeMessageMap = new HashMap<>();
    /**
     * portal name (lower-case) -> end message
     * Sent to all players in the destination world when the disable phase ends,
     * just before they are teleported to the winners destination.
     */
    private final Map<String, String> endMessageMap   = new HashMap<>();

    public MinigameConfigManager(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
        this.file   = new File(plugin.getDataFolder(), "minigame.yml");
        load();
    }

    // ---- Persistence ----

    public void load() {
        winnersDestMap.clear();
        openMessageMap.clear();
        closeMessageMap.clear();
        endMessageMap.clear();
        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.isConfigurationSection("portals")) return;

        for (String portal : cfg.getConfigurationSection("portals").getKeys(false)) {
            String key  = portal.toLowerCase();
            String path = "portals." + portal;

            String winnersDest  = cfg.getString(path + ".winners-dest");
            String openMessage  = cfg.getString(path + ".open-message");
            String closeMessage = cfg.getString(path + ".close-message");
            String endMessage   = cfg.getString(path + ".end-message");

            if (winnersDest  != null) winnersDestMap .put(key, winnersDest);
            if (openMessage  != null) openMessageMap .put(key, openMessage);
            if (closeMessage != null) closeMessageMap.put(key, closeMessage);
            if (endMessage   != null) endMessageMap  .put(key, endMessage);
        }
    }

    public void save() {
        // Collect all portal names mentioned in any map
        java.util.Set<String> portals = new java.util.HashSet<>();
        portals.addAll(winnersDestMap.keySet());
        portals.addAll(openMessageMap.keySet());
        portals.addAll(closeMessageMap.keySet());
        portals.addAll(endMessageMap.keySet());

        YamlConfiguration cfg = new YamlConfiguration();
        for (String portal : portals) {
            String path = "portals." + portal;
            if (winnersDestMap .containsKey(portal)) cfg.set(path + ".winners-dest",  winnersDestMap .get(portal));
            if (openMessageMap .containsKey(portal)) cfg.set(path + ".open-message",  openMessageMap .get(portal));
            if (closeMessageMap.containsKey(portal)) cfg.set(path + ".close-message", closeMessageMap.get(portal));
            if (endMessageMap  .containsKey(portal)) cfg.set(path + ".end-message",   endMessageMap  .get(portal));
        }

        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save minigame.yml: " + e.getMessage());
        }
    }

    // ---- API ----

    public String getWinnersDest(String portalName)  { return winnersDestMap .get(portalName.toLowerCase()); }
    public String getOpenMessage(String portalName)  { return openMessageMap .get(portalName.toLowerCase()); }
    public String getCloseMessage(String portalName) { return closeMessageMap.get(portalName.toLowerCase()); }
    public String getEndMessage(String portalName)   { return endMessageMap  .get(portalName.toLowerCase()); }

    public void setWinnersDest(String portalName, String worldName) {
        winnersDestMap.put(portalName.toLowerCase(), worldName);
        save();
    }

    public void setOpenMessage(String portalName, String message) {
        openMessageMap.put(portalName.toLowerCase(), message);
        save();
    }

    public void setCloseMessage(String portalName, String message) {
        closeMessageMap.put(portalName.toLowerCase(), message);
        save();
    }

    public void setEndMessage(String portalName, String message) {
        endMessageMap.put(portalName.toLowerCase(), message);
        save();
    }
}
