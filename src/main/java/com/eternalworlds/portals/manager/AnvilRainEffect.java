package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: every {@value #SPAWN_INTERVAL_TICKS} ticks a wave of
 * anvils is spawned at random positions within {@value #SPAWN_RADIUS} blocks of
 * the world origin (0, 0).  The wave size equals the number of active (non-spectator)
 * players in the game world, so the intensity scales with the match size.
 * Each anvil is spawned at {@value #SPAWN_Y} blocks above sea level, giving it
 * enough fall time to be visible and threatening.
 *
 * <p>Anvils deal fall damage on landing ({@code hurtEntities = true}) and do not
 * drop items ({@code dropItem = false}).  Any anvil blocks left on the ground after
 * the game ends are cleaned up automatically by the world-cleaning step.
 *
 * <p>Register this effect once during plugin startup:
 * <pre>
 *   modifierManager.registerEffect("anvil-rain", new AnvilRainEffect(plugin));
 * </pre>
 * A matching entry in {@code modifiers.yml} named {@code anvil-rain} provides
 * the display name and description shown to players.
 */
public class AnvilRainEffect implements ModifierManager.ModifierEffect {

    /** Ticks between each wave of anvil spawns (40 t = 2 s). */
    private static final int    SPAWN_INTERVAL_TICKS = 40;
    /** Radius (blocks) from the world origin within which anvils are randomly placed. */
    private static final double SPAWN_RADIUS         = 25.0;
    /** Absolute Y at which every anvil is spawned (high enough to be visible as it falls). */
    private static final int    SPAWN_Y              = 100;
    /** How many anvils to spawn per active player each wave. */
    private static final int    ANVILS_PER_PLAYER    = 5;
    /** Minimum anvils per wave regardless of player count. */
    private static final int    ANVILS_MIN           = 12;

    private final EternalWorldsPlugin     plugin;
    /** worldName (lower-case) → active spawner task */
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public AnvilRainEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey); // cancel any residual task for this world

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;

            List<Player> active = world.getPlayers().stream()
                    .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
                    .toList();
            if (active.isEmpty()) return;

            ThreadLocalRandom rng = ThreadLocalRandom.current();
            int spawnY = Math.min(SPAWN_Y, world.getMaxHeight() - 1);

            // Wave size scales with player count but never drops below ANVILS_MIN
            int waveSize = Math.max(ANVILS_MIN, active.size() * ANVILS_PER_PLAYER);
            for (int i = 0; i < waveSize; i++) {
                // Uniform random point inside a circle via rejection sampling
                double x, z;
                do {
                    x = rng.nextDouble(-SPAWN_RADIUS, SPAWN_RADIUS);
                    z = rng.nextDouble(-SPAWN_RADIUS, SPAWN_RADIUS);
                } while (x * x + z * z > SPAWN_RADIUS * SPAWN_RADIUS);

                FallingBlock anvil = world.spawnFallingBlock(
                        new Location(world, x, spawnY, z),
                        Material.ANVIL.createBlockData());
                anvil.setDropItem(false);
                anvil.setHurtEntities(true);
            }
        }, SPAWN_INTERVAL_TICKS, SPAWN_INTERVAL_TICKS);

        tasks.put(worldName.toLowerCase(), task);
    }

    @Override
    public void stop(String worldName, String portalKey) {
        BukkitTask task = tasks.remove(worldName.toLowerCase());
        if (task != null) task.cancel();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Cancels all active spawner tasks — call on server disable to ensure clean shutdown. */
    public void stopAll() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
    }
}

