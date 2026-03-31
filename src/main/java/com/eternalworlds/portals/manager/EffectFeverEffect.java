package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: every {@value #INTERVAL_TICKS} ticks (4 seconds)
 * a random potion effect is applied to every player in the game world for
 * {@value #DURATION_TICKS} ticks (4 seconds).
 *
 * <p>When a player leaves the game world (world-change or disconnect) any
 * active effects applied by this modifier are removed immediately.
 */
public class EffectFeverEffect implements ModifierManager.ModifierEffect, Listener {

    private static final int INTERVAL_TICKS = 80; // 4 s between waves
    private static final int DURATION_TICKS = 80; // each effect lasts 4 s
    private static final int AMPLIFIER      = 0;  // level I

    /** All registered potion effect types, built once at class-load time. */
    private static final PotionEffectType[] EFFECT_POOL;

    static {
        List<PotionEffectType> pool = new ArrayList<>();
        for (PotionEffectType type : PotionEffectType.values()) {
            if (type != null) pool.add(type);
        }
        EFFECT_POOL = pool.toArray(new PotionEffectType[0]);
    }

    private final EternalWorldsPlugin    plugin;
    private final Map<String, BukkitTask> tasks       = new HashMap<>();
    /** Lower-case world names that currently have the effect active. */
    private final Set<String>             activeWorlds = new HashSet<>();

    public EffectFeverEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey);
        String key = worldName.toLowerCase();
        activeWorlds.add(key);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;

            PotionEffectType chosen =
                    EFFECT_POOL[ThreadLocalRandom.current().nextInt(EFFECT_POOL.length)];
            PotionEffect effect = new PotionEffect(chosen, DURATION_TICKS, AMPLIFIER, true, false, true);

            for (Player player : world.getPlayers()) {
                player.addPotionEffect(effect);
            }

        }, INTERVAL_TICKS, INTERVAL_TICKS);

        tasks.put(key, task);
        plugin.getLogger().info("[Modifiers] EffectFever active in '" + worldName
                + "' — pool size: " + EFFECT_POOL.length);
    }

    @Override
    public void stop(String worldName, String portalKey) {
        String key = worldName.toLowerCase();
        BukkitTask t = tasks.remove(key);
        if (t != null) t.cancel();
        activeWorlds.remove(key);

        // Clear fever effects from any players still in the world
        World world = plugin.getServer().getWorld(worldName);
        if (world != null) {
            for (Player player : world.getPlayers()) {
                clearFeverEffects(player);
            }
        }
    }

    /** Cancels all active tasks — call on server disable. */
    public void stopAll() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
        activeWorlds.clear();
    }

    // ── Listeners — clear effects on world-leave / disconnect ────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String worldKey = player.getWorld().getName().toLowerCase();
        if (activeWorlds.contains(worldKey)) {
            clearFeverEffects(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        // Player moved away from an active fever world — clear their effects
        String fromKey = event.getFrom().getName().toLowerCase();
        if (activeWorlds.contains(fromKey)) {
            clearFeverEffects(event.getPlayer());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Removes every potion effect in the pool from the given player. */
    private void clearFeverEffects(Player player) {
        for (PotionEffectType type : EFFECT_POOL) {
            if (type != null && player.hasPotionEffect(type)) {
                player.removePotionEffect(type);
            }
        }
    }
}
