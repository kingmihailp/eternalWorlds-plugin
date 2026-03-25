package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Gives each player in a world a random item every 4 seconds.
 * Controlled by the portal scheduler (starts when portal goes disabled,
 * stops when portal re-enables) if the world has item-randomization=true.
 */
public class ItemRandomizationManager {

    /** 4 seconds in ticks */
    private static final long INTERVAL_TICKS = 80L;

    /** All non-legacy item materials (populated once at startup). */
    private static final List<Material> ITEM_MATERIALS;
    static {
        List<Material> mats = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isLegacy() && m.isItem() && !m.isAir()
                    && m != Material.BEDROCK
                    && m != Material.ENDER_CHEST) {
                mats.add(m);
            }
        }
        ITEM_MATERIALS = Collections.unmodifiableList(mats);
    }

    private final EternalWorldsPlugin plugin;
    private final Random random = new Random();
    /** world name (lower-case) -> active task */
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public ItemRandomizationManager(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts giving random items in the world. Safe to call if already running
     * (restarts the task).
     */
    public void startRandomization(String worldName) {
        stopRandomization(worldName);
        String key = worldName.toLowerCase();

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;
            for (Player player : world.getPlayers()) {
                Material mat = ITEM_MATERIALS.get(random.nextInt(ITEM_MATERIALS.size()));
                player.getInventory().addItem(new ItemStack(mat, 1));
            }
        }, INTERVAL_TICKS, INTERVAL_TICKS);

        tasks.put(key, task);
    }

    /** Stops the active task for this world (no-op if not running). */
    public void stopRandomization(String worldName) {
        BukkitTask task = tasks.remove(worldName.toLowerCase());
        if (task != null) task.cancel();
    }

    public boolean isRunning(String worldName) {
        return tasks.containsKey(worldName.toLowerCase());
    }

    public void cancelAll() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
    }
}
