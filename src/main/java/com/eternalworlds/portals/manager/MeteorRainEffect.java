package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: randomly falling magma meteors with fire+smoke
 * particle trails within a {@value #SPAWN_RADIUS}-block radius of the world
 * origin. Each meteor is a {@link FallingBlock} of MAGMA_BLOCK that damages
 * entities on impact.
 */
public class MeteorRainEffect implements ModifierManager.ModifierEffect {

    /** Ticks between meteor spawns (15 t = 0.75 s). */
    private static final int SPAWN_INTERVAL_TICKS   = 15;
    /** Ticks between particle trail updates (2 t). */
    private static final int PARTICLE_INTERVAL_TICKS = 2;
    /** Horizontal radius from world origin (0, 0) in which meteors spawn. */
    private static final int SPAWN_RADIUS = 25;
    /** Y level from which meteors are launched. */
    private static final int SPAWN_Y      = 80;

    private final EternalWorldsPlugin plugin;

    /** worldName (lower-case) → spawn task */
    private final Map<String, BukkitTask>       spawnTasks    = new HashMap<>();
    /** worldName (lower-case) → particle trail task */
    private final Map<String, BukkitTask>       particleTasks = new HashMap<>();
    /** worldName (lower-case) → list of currently falling meteors */
    private final Map<String, List<FallingBlock>> activeMeteors = new HashMap<>();

    public MeteorRainEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey);
        String key = worldName.toLowerCase();
        activeMeteors.put(key, new ArrayList<>());

        // ── Meteor spawner ──────────────────────────────────────────────────
        BukkitTask spawnTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;

            ThreadLocalRandom rng      = ThreadLocalRandom.current();
            double            radiusSq = (double) SPAWN_RADIUS * SPAWN_RADIUS;
            double x, z;
            do {
                x = rng.nextDouble(-SPAWN_RADIUS, SPAWN_RADIUS);
                z = rng.nextDouble(-SPAWN_RADIUS, SPAWN_RADIUS);
            } while (x * x + z * z > radiusSq);

            Location spawnLoc = new Location(world, x + 0.5, SPAWN_Y, z + 0.5);
            FallingBlock meteor = world.spawnFallingBlock(
                    spawnLoc, Material.MAGMA_BLOCK.createBlockData());
            meteor.setDropItem(false);
            meteor.setHurtEntities(true);

            activeMeteors.get(key).add(meteor);

        }, 5L, SPAWN_INTERVAL_TICKS);

        // ── Particle trail ──────────────────────────────────────────────────
        BukkitTask particleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;

            List<FallingBlock> meteors = activeMeteors.get(key);
            if (meteors == null) return;
            meteors.removeIf(fb -> !fb.isValid());

            for (FallingBlock fb : meteors) {
                Location loc = fb.getLocation();
                world.spawnParticle(Particle.FLAME,      loc, 6,  0.15, 0.15, 0.15, 0.02);
                world.spawnParticle(Particle.SMOKE,      loc, 4,  0.2,  0.2,  0.2,  0.01);
            }

        }, 1L, PARTICLE_INTERVAL_TICKS);

        spawnTasks.put(key, spawnTask);
        particleTasks.put(key, particleTask);
        plugin.getLogger().info("[Modifiers] MeteorRain active in '" + worldName + "'.");
    }

    @Override
    public void stop(String worldName, String portalKey) {
        String key = worldName.toLowerCase();
        BukkitTask s = spawnTasks.remove(key);
        if (s != null) s.cancel();
        BukkitTask p = particleTasks.remove(key);
        if (p != null) p.cancel();
        activeMeteors.remove(key);
    }

    public void stopAll() {
        spawnTasks.values().forEach(BukkitTask::cancel);
        spawnTasks.clear();
        particleTasks.values().forEach(BukkitTask::cancel);
        particleTasks.clear();
        activeMeteors.clear();
    }
}
