package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages game modifiers that can be applied to a dynamic-delay game
 * during the COUNTDOWN phase.
 *
 * Modifier definitions are persisted in {@code <plugin-folder>/modifiers.yml}.
 * During the COUNTDOWN phase players vote for a modifier; the one with the
 * most votes is applied when the game starts.  Votes are kept in memory only
 * and are cleared automatically after each round.
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

    /**
     * Optional code-based logic attached to a modifier.
     * Implementations are registered at startup via
     * {@link #registerEffect(String, ModifierEffect)} and are started/stopped
     * by {@code DynamicDelayManager} alongside the modifier's console commands.
     */
    public interface ModifierEffect {
        /** Called on the main thread when a game begins with this modifier active. */
        void start(String worldName, String portalKey);
        /** Called on the main thread when the game ends or the cycle is interrupted. */
        void stop(String worldName, String portalKey);
        /**
         * Return {@code true} if this effect provides its own item distribution
         * and the default {@link ItemRandomizationManager} must NOT be started
         * alongside it.  Defaults to {@code false}.
         */
        default boolean replacesItemDistribution() { return false; }
    }

    private final EternalWorldsPlugin plugin;
    private final File                file;

    /** modifier name (lower-case) → definition, insertion-ordered */
    private final Map<String, Modifier>             modifiers = new LinkedHashMap<>();
    /** portal key (lower-case) → { playerUUID → voted modifier name (lower-case) } */
    private final Map<String, Map<UUID, String>>    votes     = new HashMap<>();
    /** modifier name (lower-case) → optional code-based effect */
    private final Map<String, ModifierEffect>       effects   = new HashMap<>();

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

        // Merge any entries present in the bundled default resource but missing from
        // the server's file.  This ensures new modifiers added in plugin updates appear
        // automatically without requiring the admin to delete and recreate the file.
        java.io.InputStream defaultStream = plugin.getResource("modifiers.yml");
        if (defaultStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            if (defaults.isConfigurationSection("modifiers")) {
                boolean changed = false;
                for (String key : defaults.getConfigurationSection("modifiers").getKeys(false)) {
                    if (!cfg.isSet("modifiers." + key)) {
                        String src = "modifiers." + key;
                        cfg.set(src + ".display-name", defaults.getString(src + ".display-name", key));
                        cfg.set(src + ".description",  defaults.getString(src + ".description",  ""));
                        cfg.set(src + ".commands",     defaults.getStringList(src + ".commands"));
                        changed = true;
                        plugin.getLogger().info("[Modifiers] Added new modifier '" + key + "' from defaults.");
                    }
                }
                if (changed) {
                    try { cfg.save(file); } catch (IOException e) {
                        plugin.getLogger().severe("Failed to update modifiers.yml: " + e.getMessage());
                    }
                }
            }
        }

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

    // ── Built-in effects ─────────────────────────────────────────────────────

    /**
     * Associates a code-based {@link ModifierEffect} with a modifier name.
     * The effect is started/stopped by {@code DynamicDelayManager} whenever a
     * game begins or ends with that modifier active.
     */
    public void registerEffect(String modifierName, ModifierEffect effect) {
        effects.put(modifierName.toLowerCase(), effect);
    }

    /**
     * Returns the registered {@link ModifierEffect} for the given modifier name,
     * or {@code null} if no effect has been registered.
     */
    public ModifierEffect getEffect(String modifierName) {
        return effects.get(modifierName.toLowerCase());
    }

    // ── Per-round voting ─────────────────────────────────────────────────────

    /**
     * Records or updates a player's vote for the given portal round.
     *
     * @return the modifier name the player previously voted for, or {@code null}
     *         if this is a new vote
     */
    public String castVote(String portalKey, UUID playerId, String modifierName) {
        return votes.computeIfAbsent(portalKey.toLowerCase(), k -> new HashMap<>())
                    .put(playerId, modifierName.toLowerCase());
    }

    /**
     * Removes a player's vote for the given portal round.
     *
     * @return {@code true} if a vote was removed, {@code false} if the player
     *         had not voted
     */
    public boolean clearVote(String portalKey, UUID playerId) {
        Map<UUID, String> portalVotes = votes.get(portalKey.toLowerCase());
        if (portalVotes == null) return false;
        return portalVotes.remove(playerId) != null;
    }

    /**
     * Returns the modifier name the given player voted for, or {@code null} if
     * they have not voted.
     */
    public String getPlayerVote(String portalKey, UUID playerId) {
        Map<UUID, String> portalVotes = votes.get(portalKey.toLowerCase());
        return portalVotes != null ? portalVotes.get(playerId) : null;
    }

    /**
     * Returns the vote counts for every modifier that received at least one vote,
     * sorted by count descending.  The map is insertion-ordered (LinkedHashMap).
     */
    public Map<String, Integer> getVoteCounts(String portalKey) {
        Map<UUID, String> portalVotes = votes.get(portalKey.toLowerCase());
        if (portalVotes == null || portalVotes.isEmpty()) return Collections.emptyMap();
        Map<String, Integer> raw = new HashMap<>();
        for (String modName : portalVotes.values()) {
            raw.merge(modName, 1, Integer::sum);
        }
        return raw.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Returns the {@link Modifier} that received the most votes for the given portal
     * round, or {@code null} if no votes have been cast or the winning name is no
     * longer registered.  Ties are broken by whichever modifier appears first in
     * the sorted (descending) vote-count map.
     */
    public Modifier getWinner(String portalKey) {
        Map<String, Integer> counts = getVoteCounts(portalKey);
        if (counts.isEmpty()) return null;
        String winnerName = counts.keySet().iterator().next();
        return modifiers.get(winnerName);
    }

    /**
     * Clears all player votes for the given portal round.
     * Called automatically after each game starts or the countdown is cancelled.
     */
    public void clearAllVotes(String portalKey) {
        votes.remove(portalKey.toLowerCase());
    }

    /**
     * Alias for {@link #clearAllVotes(String)} — kept so that existing call-sites
     * in {@code DynamicDelayManager} continue to compile without changes.
     */
    public void clearSelection(String portalKey) {
        clearAllVotes(portalKey);
    }
}
