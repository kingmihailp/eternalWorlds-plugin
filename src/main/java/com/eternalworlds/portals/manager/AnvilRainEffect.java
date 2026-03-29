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
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: every {@value #SPAWN_INTERVAL_TICKS} ticks a falling
 * anvil is spawned {@value #SPAWN_HEIGHT_OFFSET} blocks above each non-spectator
 * player who is within {@value #SPAWN_RADIUS} blocks of the world origin (0, 0).
 * A small random horizontal scatter (±{@value #HORIZONTAL_SCATTER} blocks) is
 * applied to each spawn location to make the rain feel organic rather than perfectly
 * aimed.
 *
 * <p>Anvils deal fall damage on landing (hurtEntities = true) and do not drop items
 * (dropItem = false).  Any anvil blocks left on the ground after the game ends are
 * removed by the world-cleaning step.
 *
 * <p>Register this effect once during plugin startup:
 * <pre>
 *   modifierManager.registerEffect("anvil-rain", new AnvilRainEffect(plugin));
 * </pre>
 * A matching entry in {@code modifiers.yml} named {@code anvil-rain} provides
 * the display name and description shown to players.
 */
public class AnvilRainEffect implements ModifierManager.ModifierEffect {

    /** Ticks between each wave of spawns per player (40 t = 2 s). */
    private static final int    SPAWN_INTERVAL_TICKS = 40;
    /** Maximum distance from the world origin (0, 0) for a player to be targeted. */
    private static final double SPAWN_RADIUS         = 50.0;
    private static final double SPAWN_RADIUS_SQ      = SPAWN_RADIUS * SPAWN_RADIUS;
    /** Blocks above the player's current Y at which each anvil is spawned. */
    private static final int    SPAWN_HEIGHT_OFFSET  = 25;
    /** Maximum random horizontal offset applied in each axis (blocks). */
    private static final double HORIZONTAL_SCATTER   = 4.0;

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

            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (Player player : world.getPlayers()) {
                if (player.getGameMode() == GameMode.SPECTATOR) continue;

                Location loc = player.getLocation();
                double dx = loc.getX(), dz = loc.getZ();
                // Only target players within the configured radius of the world center
                if (dx * dx + dz * dz > SPAWN_RADIUS_SQ) continue;

                double spawnX = loc.getX() + rng.nextDouble(-HORIZONTAL_SCATTER, HORIZONTAL_SCATTER);
                double spawnZ = loc.getZ() + rng.nextDouble(-HORIZONTAL_SCATTER, HORIZONTAL_SCATTER);
                // Clamp Y to stay within world bounds
                double spawnY = Math.min(loc.getY() + SPAWN_HEIGHT_OFFSET, world.getMaxHeight() - 1);

                FallingBlock anvil = world.spawnFallingBlock(
                        new Location(world, spawnX, spawnY, spawnZ),
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
