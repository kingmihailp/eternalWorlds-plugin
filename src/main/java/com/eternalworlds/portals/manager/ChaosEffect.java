package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: every {@value #CHAOS_INTERVAL_TICKS} ticks,
 * {@value #BLOCKS_PER_TICK} random blocks are placed at random air positions
 * within a {@value #CHAOS_RADIUS}-block radius of the world origin between
 * Y={@value #CHAOS_MIN_Y} and Y={@value #CHAOS_MAX_Y}.
 * With a 1-in-{@value #MOB_SPAWN_CHANCE} chance a random mob is also spawned
 * at a random position in the same area.
 */
public class ChaosEffect implements ModifierManager.ModifierEffect {

    private static final int CHAOS_INTERVAL_TICKS = 20;   // 1 second per wave
    private static final int CHAOS_RADIUS          = 25;
    private static final int CHAOS_MIN_Y           = -5;
    private static final int CHAOS_MAX_Y           = 76;
    private static final int BLOCKS_PER_TICK       = 5;   // blocks attempted per wave
    private static final int MOB_SPAWN_CHANCE      = 4;   // 1-in-N chance to spawn a mob

    /** Blocks that must never appear as chaos placements. */
    private static final Set<Material> EXCLUDED_BLOCKS = Set.of(
            Material.BEDROCK,
            Material.ENDER_CHEST,
            Material.BARRIER,
            Material.LIGHT,
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK,
            Material.STRUCTURE_VOID,
            Material.JIGSAW,
            Material.DEBUG_STICK,
            Material.END_PORTAL,
            Material.END_GATEWAY,
            Material.NETHER_PORTAL,
            Material.PISTON_HEAD,
            Material.MOVING_PISTON
    );

    /** Mob types that can be spawned during chaos. */
    private static final EntityType[] MOB_POOL = {
            EntityType.ZOMBIE,          EntityType.SKELETON,       EntityType.CREEPER,
            EntityType.SPIDER,          EntityType.CAVE_SPIDER,    EntityType.ENDERMAN,
            EntityType.WITCH,           EntityType.ZOMBIE_VILLAGER,EntityType.HUSK,
            EntityType.DROWNED,         EntityType.STRAY,          EntityType.PHANTOM,
            EntityType.SILVERFISH,      EntityType.SLIME,          EntityType.MAGMA_CUBE,
            EntityType.BLAZE,           EntityType.WITHER_SKELETON,EntityType.PILLAGER,
            EntityType.VEX,             EntityType.EVOKER,         EntityType.VINDICATOR,
            EntityType.COW,             EntityType.PIG,            EntityType.SHEEP,
            EntityType.CHICKEN,         EntityType.WOLF,           EntityType.CAT,
            EntityType.OCELOT,          EntityType.HORSE,          EntityType.DONKEY,
            EntityType.VILLAGER,        EntityType.IRON_GOLEM,     EntityType.SNOW_GOLEM,
            EntityType.BAT,             EntityType.SQUID,          EntityType.DOLPHIN,
            EntityType.TURTLE,          EntityType.FOX,            EntityType.BEE,
            EntityType.POLAR_BEAR,      EntityType.PANDA,
    };

    /** All placeable blocks except the excluded set, built once at class load. */
    private static final Material[] BLOCK_POOL;

    static {
        List<Material> pool = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isBlock() && !m.isAir() && !EXCLUDED_BLOCKS.contains(m)) {
                pool.add(m);
            }
        }
        BLOCK_POOL = pool.toArray(new Material[0]);
    }

    private final EternalWorldsPlugin     plugin;
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public ChaosEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;

            ThreadLocalRandom rng      = ThreadLocalRandom.current();
            double            radiusSq = (double) CHAOS_RADIUS * CHAOS_RADIUS;

            // ── Random block placements ──────────────────────────────────────
            for (int i = 0; i < BLOCKS_PER_TICK; i++) {
                double x, z;
                do {
                    x = rng.nextDouble(-CHAOS_RADIUS, CHAOS_RADIUS);
                    z = rng.nextDouble(-CHAOS_RADIUS, CHAOS_RADIUS);
                } while (x * x + z * z > radiusSq);

                int   y     = rng.nextInt(CHAOS_MIN_Y, CHAOS_MAX_Y + 1);
                Block block = world.getBlockAt((int) Math.floor(x), y, (int) Math.floor(z));
                if (block.isEmpty()) {
                    block.setType(BLOCK_POOL[rng.nextInt(BLOCK_POOL.length)], false);
                }
            }

            // ── Random mob spawn (1-in-N chance per wave) ────────────────────
            if (rng.nextInt(MOB_SPAWN_CHANCE) == 0) {
                double x, z;
                do {
                    x = rng.nextDouble(-CHAOS_RADIUS, CHAOS_RADIUS);
                    z = rng.nextDouble(-CHAOS_RADIUS, CHAOS_RADIUS);
                } while (x * x + z * z > radiusSq);

                int      y       = rng.nextInt(CHAOS_MIN_Y, CHAOS_MAX_Y + 1);
                Location mobLoc  = new Location(world, x + 0.5, y, z + 0.5);
                EntityType type  = MOB_POOL[rng.nextInt(MOB_POOL.length)];
                try {
                    world.spawnEntity(mobLoc, type);
                } catch (Exception ignored) {
                    // Some entity types may fail in certain world conditions — skip silently
                }
            }

        }, CHAOS_INTERVAL_TICKS, CHAOS_INTERVAL_TICKS);

        tasks.put(worldName.toLowerCase(), task);
        plugin.getLogger().info("[Modifiers] Chaos active in '" + worldName
                + "' — block pool: " + BLOCK_POOL.length + ", mob pool: " + MOB_POOL.length);
    }

    @Override
    public void stop(String worldName, String portalKey) {
        BukkitTask t = tasks.remove(worldName.toLowerCase());
        if (t != null) t.cancel();
    }

    /** Cancels all active tasks — call on server disable. */
    public void stopAll() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
    }
}
