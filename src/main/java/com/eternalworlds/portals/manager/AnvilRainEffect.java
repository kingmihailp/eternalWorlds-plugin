package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftFallingBlock;
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
 * the world origin (0, 0).
 *
 * <p>Fall damage is applied via NMS because the Bukkit 1.21.1 API does not expose
 * {@code setFallDamageAmount} / {@code setMaxDamage} on {@link FallingBlock}.
 */
public class AnvilRainEffect implements ModifierManager.ModifierEffect {

    /** Ticks between each wave of anvil spawns (40 t = 2 s). */
    private static final int    SPAWN_INTERVAL_TICKS = 40;
    /** Radius (blocks) from the world origin within which anvils are randomly placed. */
    private static final double SPAWN_RADIUS         = 25.0;
    /** Absolute Y at which every anvil is spawned. */
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

            ThreadLocalRandom rng    = ThreadLocalRandom.current();
            int               spawnY = Math.min(SPAWN_Y, world.getMaxHeight() - 1);
            int               count  = Math.max(ANVILS_MIN, active.size() * ANVILS_PER_PLAYER);

            for (int i = 0; i < count; i++) {
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

                // Bukkit 1.21.1 does not expose fall-damage setters on FallingBlock;
                // set the NMS fields directly (Mojang-mapped: hurtEntities, fallDamageAmount, fallDamageMax).
                FallingBlockEntity nms = ((CraftFallingBlock) anvil).getHandle();
                nms.fallDamageAmount = 2.0f;  // 2 HP per block fallen
                nms.fallDamageMax    = 40;    // cap at 40 HP (20 hearts)
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
