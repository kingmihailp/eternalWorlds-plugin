package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in modifier effect: at game start every player receives a
 * Knockback-100 stick and Unbreaking-10 elytra. Every {@value #INTERVAL_TICKS}
 * ticks (4 seconds) each player is also given one Wind Charge.
 */
public class PushersEffect implements ModifierManager.ModifierEffect {

    private static final int INTERVAL_TICKS = 80; // 4 s

    private final EternalWorldsPlugin     plugin;
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public PushersEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public boolean replacesItemDistribution() { return true; }

    @Override
    public void start(String worldName, String portalKey) {
        stop(worldName, portalKey);

        // Give starting kit immediately to all players in the world
        World world = plugin.getServer().getWorld(worldName);
        if (world != null) {
            giveStartKit(world.getPlayers());
        }

        // Every 4 seconds: give each player 1 Wind Charge
        BukkitTask task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            World w = plugin.getServer().getWorld(worldName);
            if (w == null) return;
            ItemStack windCharge = new ItemStack(Material.WIND_CHARGE, 1);
            for (Player player : w.getPlayers()) {
                player.getInventory().addItem(windCharge.clone());
            }
        }, INTERVAL_TICKS, INTERVAL_TICKS);

        tasks.put(worldName.toLowerCase(), task);
        plugin.getLogger().info("[Modifiers] Pushers active in '" + worldName + "'.");
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void giveStartKit(List<Player> players) {
        ItemStack stick = new ItemStack(Material.STICK);
        stick.addUnsafeEnchantment(Enchantment.KNOCKBACK, 100);

        ItemStack elytra = new ItemStack(Material.ELYTRA);
        elytra.addUnsafeEnchantment(Enchantment.UNBREAKING, 10);

        for (Player player : players) {
            player.getInventory().addItem(stick.clone(), elytra.clone());
        }
    }
}
