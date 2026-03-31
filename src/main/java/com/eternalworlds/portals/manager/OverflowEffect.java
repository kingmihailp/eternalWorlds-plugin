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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;



/**
 * Built-in modifier effect: every {@value #INTERVAL_TICKS} ticks (4 seconds)
 * a random item is chosen and 64 of it are given to every player in the game world.
 */
public class OverflowEffect implements ModifierManager.ModifierEffect {

    private static final int INTERVAL_TICKS = 80; // 4 s
    private static final int AMOUNT         = 64;

    private static final Set<Material> EXCLUDED = Set.of(
            Material.BEDROCK,
            Material.ENDER_CHEST,
            Material.BARRIER,
            Material.LIGHT,
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.COMMAND_BLOCK_MINECART,
            Material.STRUCTURE_BLOCK,
            Material.STRUCTURE_VOID,
            Material.JIGSAW,
            Material.DEBUG_STICK,
            Material.KNOWLEDGE_BOOK,
            Material.WRITTEN_BOOK
    );

    private static final Material[] ITEM_POOL;

    static {
        List<Material> pool = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isItem() && !m.isAir() && !EXCLUDED.contains(m)) {
                pool.add(m);
            }
        }
        ITEM_POOL = pool.toArray(new Material[0]);
    }

    private final EternalWorldsPlugin     plugin;
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public OverflowEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

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
                // Each player gets their own random item — 64 of it
                Material chosen = ITEM_POOL[rng.nextInt(ITEM_POOL.length)];
                int remaining = AMOUNT;
                while (remaining > 0) {
                    int give = Math.min(remaining, chosen.getMaxStackSize());
                    player.getInventory().addItem(new ItemStack(chosen, give));
                    remaining -= give;
                }
            }

        }, INTERVAL_TICKS, INTERVAL_TICKS);

        tasks.put(worldName.toLowerCase(), task);
        plugin.getLogger().info("[Modifiers] Overflow active in '" + worldName + "'.");
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
