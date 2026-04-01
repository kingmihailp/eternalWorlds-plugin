package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: randomly falling magma meteors with fire+smoke
 * particle trails within a {@value #SPAWN_RADIUS}-block radius of the world
 * origin. Each meteor is a {@link FallingBlock} of MAGMA_BLOCK with a slight
 * random angle. On contact with a block or a player the meteor explodes.
 */
public class MeteorRainEffect implements ModifierManager.ModifierEffect, Listener {

    /** Ticks between meteor spawns — two meteors per interval. */
    private static final int SPAWN_INTERVAL_TICKS    = 8;
    /** Ticks between particle trail updates. */
    private static final int PARTICLE_INTERVAL_TICKS = 2;
    /** Horizontal radius from world origin (0, 0) in which meteors spawn. */
    private static final int SPAWN_RADIUS = 25;
    /** Y level from which meteors are launched. */
    private static final int SPAWN_Y      = 80;
    /** Explosion power (no block damage). */
    private static final float EXPLOSION_POWER = 3.0f;
    /** Maximum random horizontal velocity component (angle of fall). */
    private static final double MAX_ANGLE_VELOCITY = 0.35;

    private final EternalWorldsPlugin plugin;

    /** worldName (lower-case) → spawn task */
    private final Map<String, BukkitTask>    spawnTasks    = new HashMap<>();
    /** worldName (lower-case) → particle trail task */
    private final Map<String, BukkitTask>    particleTasks = new HashMap<>();
    /** UUIDs of all currently tracked meteor entities (across all worlds). */
    private final Set<UUID>                  meteorUUIDs   = new HashSet<>();
    /** worldName (lower-case) → list of currently falling meteors */
    private final Map<String, List<FallingBlock>> activeMeteors = new HashMap<>();

    public MeteorRainEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey);
        String key = worldName.toLowerCase();
        activeMeteors.put(key, new ArrayList<>());

        // ── Meteor spawner: 2 meteors every SPAWN_INTERVAL_TICKS ───────────
        BukkitTask spawnTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;
            for (int i = 0; i < 2; i++) {
                spawnMeteor(world, key);
            }
        }, 5L, SPAWN_INTERVAL_TICKS);

        // ── Particle trail ──────────────────────────────────────────────────
        BukkitTask particleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;
            List<FallingBlock> meteors = activeMeteors.get(key);
            if (meteors == null) return;
            meteors.removeIf(fb -> {
                if (!fb.isValid()) { meteorUUIDs.remove(fb.getUniqueId()); return true; }
                return false;
            });
            for (FallingBlock fb : meteors) {
                Location loc = fb.getLocation();
                world.spawnParticle(Particle.FLAME, loc, 8,  0.15, 0.15, 0.15, 0.03);
                world.spawnParticle(Particle.SMOKE, loc, 5,  0.2,  0.2,  0.2,  0.01);
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
        List<FallingBlock> meteors = activeMeteors.remove(key);
        if (meteors != null) {
            for (FallingBlock fb : meteors) meteorUUIDs.remove(fb.getUniqueId());
        }
    }

    public void stopAll() {
        spawnTasks.values().forEach(BukkitTask::cancel);
        spawnTasks.clear();
        particleTasks.values().forEach(BukkitTask::cancel);
        particleTasks.clear();
        activeMeteors.clear();
        meteorUUIDs.clear();
    }

    // ── Explosion listeners ──────────────────────────────────────────────────

    /** Meteor touches a block — explode instead of placing. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeteorLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fb)) return;
        if (!meteorUUIDs.remove(fb.getUniqueId())) return;

        event.setCancelled(true); // don't place the magma block
        removeMeteor(fb);
        explode(fb.getLocation());
    }

    /** Meteor hits a player directly — explode on contact. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeteorHitEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof FallingBlock fb)) return;
        if (!meteorUUIDs.remove(fb.getUniqueId())) return;

        event.setCancelled(true); // explosion handles the damage
        removeMeteor(fb);
        explode(fb.getLocation());
        fb.remove();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void spawnMeteor(World world, String key) {
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
        meteor.setHurtEntities(false); // explosion handles damage

        // Apply slight random angle
        double angle    = rng.nextDouble(0, Math.PI * 2);
        double strength = rng.nextDouble(0.1, MAX_ANGLE_VELOCITY);
        meteor.setVelocity(new Vector(
                Math.cos(angle) * strength,
                -0.5,
                Math.sin(angle) * strength));

        meteorUUIDs.add(meteor.getUniqueId());
        activeMeteors.get(key).add(meteor);
    }

    private void removeMeteor(FallingBlock fb) {
        String key = fb.getWorld().getName().toLowerCase();
        List<FallingBlock> list = activeMeteors.get(key);
        if (list != null) list.remove(fb);
    }

    private void explode(Location loc) {
        loc.getWorld().createExplosion(loc, EXPLOSION_POWER, true, false);
    }
}
