package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: every {@value #INTERVAL_TICKS} ticks (4 seconds)
 * each player in the game world receives a random mob spawn egg instead of the
 * default random item.
 */
public class MobificationEffect implements ModifierManager.ModifierEffect {

    private static final int INTERVAL_TICKS = 80; // 4 s

    /** All spawn-egg materials, built once at class-load time. */
    private static final Material[] EGG_POOL;

    static {
        List<Material> pool = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.name().endsWith("_SPAWN_EGG")) {
                pool.add(m);
            }
        }
        EGG_POOL = pool.toArray(new Material[0]);
    }

    private final EternalWorldsPlugin     plugin;
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public MobificationEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean replacesItemDistribution() { return true; }

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) return;

            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (Player player : players) {
                Material egg = EGG_POOL[rng.nextInt(EGG_POOL.length)];
                player.getInventory().addItem(new ItemStack(egg, 1));
            }
        }, INTERVAL_TICKS, INTERVAL_TICKS);

        tasks.put(worldName.toLowerCase(), task);
        plugin.getLogger().info("[Modifiers] Mobification active in '" + worldName
                + "' — egg pool: " + EGG_POOL.length);
    }

    @Override
    public void stop(String worldName, String portalKey) {
        BukkitTask t = tasks.remove(worldName.toLowerCase());
        if (t != null) t.cancel();
    }

    public void stopAll() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
    }
}
