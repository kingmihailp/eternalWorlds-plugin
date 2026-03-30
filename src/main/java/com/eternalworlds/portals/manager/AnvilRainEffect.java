package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: every {@value #SPAWN_INTERVAL_TICKS} ticks a wave of
 * anvil blocks is placed in mid-air within {@value #SPAWN_RADIUS} blocks of the world
 * origin (0, 0).  Because ANVIL is a gravity-affected block, the server immediately
 * converts it into a naturally-falling entity that carries the vanilla fall-damage
 * values (2 HP / block, max 40 HP) — no NMS or reflection required.
 *
 * <p>Landed anvils become solid ANVIL blocks on the ground and are removed
 * automatically by the world-cleaning step at the end of each round.
 */
public class AnvilRainEffect implements ModifierManager.ModifierEffect {

    /** Ticks between each wave (40 t = 2 s). */
    private static final int    SPAWN_INTERVAL_TICKS = 40;
    /** Radius (blocks) from the world origin within which anvils are randomly placed. */
    private static final double SPAWN_RADIUS         = 25.0;
    /** Y at which each anvil block is placed; it then falls from there. */
    private static final int    SPAWN_Y              = 100;
    /** Anvils spawned per active (non-spectator) player each wave. */
    private static final int    ANVILS_PER_PLAYER    = 8;
    /** Minimum anvils per wave regardless of player count. */
    private static final int    ANVILS_MIN           = 20;

    private final EternalWorldsPlugin     plugin;
    /** worldName (lower-case) → active spawner task */
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public AnvilRainEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;

            List<Player> active = world.getPlayers().stream()
                    .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
                    .toList();
            if (active.isEmpty()) return;

            ThreadLocalRandom rng   = ThreadLocalRandom.current();
            int spawnY              = Math.min(SPAWN_Y, world.getMaxHeight() - 1);
            int count               = Math.max(ANVILS_MIN, active.size() * ANVILS_PER_PLAYER);

            for (int i = 0; i < count; i++) {
                // Uniform random point inside a circle via rejection sampling
                double x, z;
                do {
                    x = rng.nextDouble(-SPAWN_RADIUS, SPAWN_RADIUS);
                    z = rng.nextDouble(-SPAWN_RADIUS, SPAWN_RADIUS);
                } while (x * x + z * z > SPAWN_RADIUS * SPAWN_RADIUS);

                // Place an ANVIL block in the air with physics enabled.
                // ANVIL is gravity-affected, so the server immediately converts it into
                // a FallingBlockEntity that uses vanilla damage (2 HP/block, max 40 HP).
                Block block = world.getBlockAt((int) Math.floor(x), spawnY, (int) Math.floor(z));
                block.setType(Material.ANVIL, true);
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
