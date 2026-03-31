package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * Built-in modifier effect: every block or mob drop in the game world has its
 * stack size set to 64 — players receive full stacks instead of single items.
 *
 * <p>Only the active game world is affected; all other worlds are untouched.
 */
public class OverflowEffect implements ModifierManager.ModifierEffect, Listener {

    private static final int STACK_SIZE = 64;

    private final EternalWorldsPlugin plugin;
    /** Lower-case world names for which overflow is currently active. */
    private final Set<String> activeWorlds = new HashSet<>();

    public OverflowEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public void start(String worldName, String portalKey) {
        activeWorlds.add(worldName.toLowerCase());
        plugin.getLogger().info("[Modifiers] Overflow active in world '" + worldName + "'.");
    }

    @Override
    public void stop(String worldName, String portalKey) {
        activeWorlds.remove(worldName.toLowerCase());
    }

    public void stopAll() {
        activeWorlds.clear();
    }

    // ── Event handlers ───────────────────────────────────────────────────────

    /** Sets every block drop to a stack of 64. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!activeWorlds.contains(event.getBlock().getWorld().getName().toLowerCase())) return;
        for (Item item : event.getItems()) {
            ItemStack stack = item.getItemStack();
            stack.setAmount(Math.min(STACK_SIZE, stack.getMaxStackSize()));
            item.setItemStack(stack);
        }
    }

    /** Sets every mob drop to a stack of 64. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!activeWorlds.contains(event.getEntity().getWorld().getName().toLowerCase())) return;
        for (ItemStack drop : event.getDrops()) {
            drop.setAmount(Math.min(STACK_SIZE, drop.getMaxStackSize()));
        }
    }
}
