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
 * one random item is chosen and given to <em>every</em> player in the game world —
 * so all players receive the exact same item each wave ("fair play").
 *
 * <p>The item pool is the same full-registry pool used by {@link RandomizationEffect}:
 * every obtainable {@link Material} except admin/creative-only items.
 */
public class FairPlayEffect implements ModifierManager.ModifierEffect {

    /** Ticks between each item distribution wave (80 t = 4 s). */
    private static final int INTERVAL_TICKS = 80;

    /** Materials that are never handed out (mirrors RandomizationEffect.EXCLUDED). */
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

    /** Full item pool — built once at class-load time. */
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

    private final EternalWorldsPlugin      plugin;
    private final Map<String, BukkitTask>  tasks = new HashMap<>();

    public FairPlayEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;

            List<Player> players = world.getPlayers();
            if (players.isEmpty()) return;

            // Pick ONE random item for this wave — same for every player.
            Material chosen = ITEM_POOL[ThreadLocalRandom.current().nextInt(ITEM_POOL.length)];
            ItemStack stack = new ItemStack(chosen, 1);

            for (Player player : players) {
                player.getInventory().addItem(stack.clone());
            }

        }, INTERVAL_TICKS, INTERVAL_TICKS);

        tasks.put(worldName.toLowerCase(), task);
        plugin.getLogger().info("[Modifiers] FairPlay active in '" + worldName
                + "' — item pool size: " + ITEM_POOL.length);
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
