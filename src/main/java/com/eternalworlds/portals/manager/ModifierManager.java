package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages game modifiers that can be applied to a dynamic-delay game
 * during the COUNTDOWN phase.
 *
 * Modifier definitions are persisted in {@code <plugin-folder>/modifiers.yml}.
 * The currently-selected modifier per portal is kept in memory only —
 * it is a per-round choice and does not need to survive restarts.
 *
 * YAML structure:
 * <pre>
 * modifiers:
 *   speed:
 *     display-name: "&#FFD700&lСкорость"
 *     description: "Все игроки получают эффект скорости II"
 *     commands:
 *       - "effect give @a[world={world}] speed 999999 1"
 * </pre>
 *
 * Placeholders supported in commands: {@code {portal}}, {@code {world}}.
 */
public class ModifierManager {

    /**
     * Immutable definition of a single modifier.
     * {@code displayName} stores the raw string (with {@code &} color codes);
     * callers should pass it through {@link com.eternalworlds.portals.util.ColorUtil#parse}
     * before displaying.
     */
    public record Modifier(
            String       name,
            String       displayName,
            String       description,
            List<String> commands
    ) {}

    private final EternalWorldsPlugin plugin;
    private final File                file;

    /** modifier name (lower-case) → definition, insertion-ordered */
    private final Map<String, Modifier> modifiers  = new LinkedHashMap<>();
    /** portal key (lower-case) → selected modifier name for the current round */
    private final Map<String, String>   selections = new HashMap<>();

    public ModifierManager(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
        this.file   = new File(plugin.getDataFolder(), "modifiers.yml");
        load();
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    public void load() {
        modifiers.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.isConfigurationSection("modifiers")) return;
        for (String key : cfg.getConfigurationSection("modifiers").getKeys(false)) {
            String path        = "modifiers." + key;
            String displayName = cfg.getString(path + ".display-name", key);
            String description = cfg.getString(path + ".description",  "");
            List<String> cmds  = cfg.getStringList(path + ".commands");
            modifiers.put(key.toLowerCase(), new Modifier(
                    key.toLowerCase(), displayName, description, List.copyOf(cmds)));
        }
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Modifier m : modifiers.values()) {
            String path = "modifiers." + m.name();
            cfg.set(path + ".display-name", m.displayName());
            cfg.set(path + ".description",  m.description());
            cfg.set(path + ".commands",     new ArrayList<>(m.commands()));
        }
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save modifiers.yml: " + e.getMessage());
        }
    }

    // ── Modifier definitions ─────────────────────────────────────────────────

    /** Returns all registered modifiers in definition order. */
    public Collection<Modifier> getModifiers() {
        return Collections.unmodifiableCollection(modifiers.values());
    }

    /** Returns the modifier with the given name, or {@code null} if not found. */
    public Modifier getModifier(String name) {
        return modifiers.get(name.toLowerCase());
    }

    public void addModifier(String name, String displayName, String description, List<String> commands) {
        modifiers.put(name.toLowerCase(),
                new Modifier(name.toLowerCase(), displayName, description, List.copyOf(commands)));
        save();
    }

    public boolean removeModifier(String name) {
        boolean removed = modifiers.remove(name.toLowerCase()) != null;
        if (removed) save();
        return removed;
    }

    // ── Per-round selection ──────────────────────────────────────────────────

    /**
     * Records the chosen modifier for the current round of the given portal.
     * Overwrites any previous selection.
     */
    public void setSelection(String portalKey, String modifierName) {
        selections.put(portalKey.toLowerCase(), modifierName.toLowerCase());
    }

    /** Removes any modifier selection for the given portal. */
    public void clearSelection(String portalKey) {
        selections.remove(portalKey.toLowerCase());
    }

    /**
     * Returns the selected {@link Modifier} for the given portal's current round,
     * or {@code null} if nothing is selected or the stored name no longer exists.
     */
    public Modifier getSelection(String portalKey) {
        String name = selections.get(portalKey.toLowerCase());
        return name != null ? modifiers.get(name) : null;
    }
}
