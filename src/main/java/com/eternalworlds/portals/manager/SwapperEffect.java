package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.util.ColorUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: every {@value #SWAP_INTERVAL_TICKS} ticks all
 * active (non-spectator) players in the game world are randomly shuffled and
 * teleported to each other's positions.
 *
 * <p>Positions are captured before any teleports so the swap is always consistent:
 * player A goes where player B was standing, player B where player C was, etc.
 * Players in SPECTATOR mode are excluded.
 */
public class SwapperEffect implements ModifierManager.ModifierEffect {

    /** Ticks between swaps (300 t = 15 s). */
    private static final int SWAP_INTERVAL_TICKS = 300;

    private final EternalWorldsPlugin     plugin;
    /** worldName (lower-case) → active swap task */
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public SwapperEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey);

        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) return;

            List<Player> active = new ArrayList<>(world.getPlayers().stream()
                    .filter(p -> p.getGameMode() != GameMode.SPECTATOR)
                    .toList());
            if (active.size() < 2) return;

            // Snapshot all locations before any teleport
            List<Location> locations = new ArrayList<>();
            for (Player p : active) locations.add(p.getLocation().clone());

            // Shuffle destination assignment: player[i] → locations[perm[i]]
            List<Integer> perm = new ArrayList<>();
            for (int i = 0; i < active.size(); i++) perm.add(i);
            // Ensure no player teleports to their own position
            do { Collections.shuffle(perm, ThreadLocalRandom.current()); }
            while (hasFixedPoint(perm));

            for (int i = 0; i < active.size(); i++) {
                active.get(i).teleportAsync(locations.get(perm.get(i)));
            }

            String msg = ColorUtil.parse("&d&l★ Сваппер: &r&dигроки поменялись местами!");
            for (Player p : world.getPlayers()) p.sendMessage(msg);

        }, SWAP_INTERVAL_TICKS, SWAP_INTERVAL_TICKS);

        tasks.put(worldName.toLowerCase(), task);
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns true if any element in the permutation maps to its own index (fixed point). */
    private static boolean hasFixedPoint(List<Integer> perm) {
        for (int i = 0; i < perm.size(); i++) {
            if (perm.get(i) == i) return true;
        }
        return false;
    }
}
