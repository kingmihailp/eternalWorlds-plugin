package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: replaces all block and mob drops with a random item
 * drawn from the full Minecraft item registry (every obtainable item except
 * bedrock and ender chest).
 *
 * <p>The pool is built once at class-load time by iterating {@link Material#values()}
 * and keeping only materials that satisfy {@link Material#isItem()}, are not air,
 * and are not in the small exclusion list.  This automatically includes every new
 * item added by future Minecraft versions.
 *
 * <p>Only the active game world is affected — all other worlds are untouched.
 */
public class RandomizationEffect implements ModifierManager.ModifierEffect, Listener {

    /**
     * Materials that should never appear as randomized drops even though they are
     * technically obtainable items (admin/creative-only blocks, illegal items, etc.).
     */
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
            Material.WRITTEN_BOOK   // contains arbitrary text — skip to avoid confusion
    );

    /** Full drop pool — every obtainable item minus the exclusion list. */
    private static final Material[] DROP_POOL;

    static {
        List<Material> pool = new ArrayList<>();
        for (Material m : Material.values()) {
            if (m.isItem() && !m.isAir() && !EXCLUDED.contains(m)) {
                pool.add(m);
            }
        }
        DROP_POOL = pool.toArray(new Material[0]);
    }

    private final EternalWorldsPlugin plugin;
    /** Lower-case world names for which drop randomization is currently active. */
    private final Set<String>         activeWorlds = new HashSet<>();

    public RandomizationEffect(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── ModifierEffect ───────────────────────────────────────────────────────

    @Override
    public void start(String worldName, String portalKey) {
        activeWorlds.add(worldName.toLowerCase());
        plugin.getLogger().info("[Modifiers] Randomization active in world '" + worldName
                + "' — pool size: " + DROP_POOL.length + " items.");
    }

    @Override
    public void stop(String worldName, String portalKey) {
        activeWorlds.remove(worldName.toLowerCase());
    }

    /** Clears all active worlds — call on server disable for a clean shutdown. */
    public void stopAll() {
        activeWorlds.clear();
    }

    // ── Event handlers ───────────────────────────────────────────────────────

    /** Replaces every item a block would drop with 5–12 random items from the pool. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!activeWorlds.contains(event.getBlock().getWorld().getName().toLowerCase())) return;
        ThreadLocalRandom rng   = ThreadLocalRandom.current();
        int               count = rng.nextInt(5, 13); // 5..12 inclusive
        Location          loc   = event.getBlock().getLocation().add(0.5, 0.5, 0.5);

        // Reuse existing item entities (modify in-place to avoid extra spawns)
        List<Item> existing = event.getItems();
        int modified = 0;
        for (Item item : existing) {
            item.getItemStack().setType(randomMaterial(rng));
            item.getItemStack().setAmount(1);
            modified++;
        }
        // Spawn any additional items beyond what the block naturally dropped
        for (int i = modified; i < count; i++) {
            event.getBlock().getWorld().dropItemNaturally(loc, new ItemStack(randomMaterial(rng)));
        }
    }

    /** Replaces every item a mob would drop with 5–12 random items from the pool. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!activeWorlds.contains(event.getEntity().getWorld().getName().toLowerCase())) return;
        ThreadLocalRandom rng   = ThreadLocalRandom.current();
        int               count = rng.nextInt(5, 13); // 5..12 inclusive

        event.getDrops().clear();
        for (int i = 0; i < count; i++) {
            event.getDrops().add(new ItemStack(randomMaterial(rng)));
        }
        event.setDroppedExp(0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Material randomMaterial(ThreadLocalRandom rng) {
        return DROP_POOL[rng.nextInt(DROP_POOL.length)];
    }
}
