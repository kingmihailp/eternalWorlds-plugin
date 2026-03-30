package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

/**
 * Built-in modifier effect: lava rises from Y={@value #LAVA_START_Y} up to
 * Y={@value #LAVA_END_Y} layer by layer within a {@value #LAVA_RADIUS}-block
 * radius of the world origin (0, 0).
 *
 * <p>One layer is filled every {@value #LAYER_INTERVAL_TICKS} ticks.  Only
 * empty (air) blocks are replaced — solid blocks such as bedrock columns are
 * left untouched, so players can survive by climbing higher.
 *
 * <p>Placed lava blocks are removed automatically when the world is cleaned
 * at the end of the round; no separate cleanup logic is required.
 */
public class FloorIsLavaEffect implements ModifierManager.ModifierEffect {

    /** Y level at which lava begins rising. */
    private static final int LAVA_START_Y       = -5;
    /** Highest Y level filled by lava. */
    private static final int LAVA_END_Y         = 77;
    /** Radius (blocks) from world origin in which lava is placed. */
    private static final int LAVA_RADIUS        = 25;
    /** Ticks between each rising layer (80 t = 4 s). */
    private static final int LAYER_INTERVAL_TICKS = 80;
    /** Announce the current lava level every N layers. */
    private static final int ANNOUNCE_EVERY_N   = 8;

    private final EternalWorldsPlugin    plugin;
    /** worldName (lower-case) → active rising task */
    private final Map<String, BukkitTask> tasks    = new HashMap<>();
    /** worldName (lower-case) → current lava Y */
    private final Map<String, Integer>   currentY  = new HashMap<>();

    public FloorIsLavaEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey);
        currentY.put(worldName.toLowerCase(), LAVA_START_Y);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;

            int y = currentY.getOrDefault(worldName.toLowerCase(), LAVA_START_Y);
            if (y > LAVA_END_Y) return; // fully risen — keep task alive but do nothing

            // Fill one lava layer inside the circle
            int radiusSq = LAVA_RADIUS * LAVA_RADIUS;
            for (int x = -LAVA_RADIUS; x <= LAVA_RADIUS; x++) {
                for (int z = -LAVA_RADIUS; z <= LAVA_RADIUS; z++) {
                    if (x * x + z * z > radiusSq) continue;
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isEmpty()) {
                        block.setType(Material.LAVA, false);
                    }
                }
            }

            // Periodic warning
            int layerIndex = y - LAVA_START_Y;
            if (layerIndex % ANNOUNCE_EVERY_N == 0) {
                String warning = ColorUtil.parse("&c&l🌋 Лава поднимается! &eТекущий уровень: &cY=" + y);
                for (Player p : world.getPlayers()) p.sendMessage(warning);
            }

            currentY.put(worldName.toLowerCase(), y + 1);

        }, LAYER_INTERVAL_TICKS, LAYER_INTERVAL_TICKS);

        tasks.put(worldName.toLowerCase(), task);

        // Announce start to all players currently in the world
        World world = plugin.getServer().getWorld(worldName);
        if (world != null) {
            String start = ColorUtil.parse("&c&l🌋 Пол — это лава! &eЛава начинает подниматься снизу!");
            for (Player p : world.getPlayers()) p.sendMessage(start);
        }
    }

    @Override
    public void stop(String worldName, String portalKey) {
        BukkitTask t = tasks.remove(worldName.toLowerCase());
        if (t != null) t.cancel();
        currentY.remove(worldName.toLowerCase());
    }

    /** Cancels all active tasks — call on server disable. */
    public void stopAll() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
        currentY.clear();
    }
}
