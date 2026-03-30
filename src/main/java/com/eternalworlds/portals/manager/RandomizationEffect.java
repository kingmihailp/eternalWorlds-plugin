package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Built-in modifier effect: replaces all block and mob drops with random items
 * chosen from a curated pool.  Only affects the specific game world(s) in which
 * the modifier is active — every other world is untouched.
 *
 * <p>The effect is active for the duration of one game round.  {@link #start}
 * registers the world; {@link #stop} deregisters it.  The listener itself stays
 * registered with Bukkit for the lifetime of the plugin (registered once in
 * {@code EternalWorldsPlugin.onEnable}) and simply no-ops for inactive worlds.
 *
 * <p>Register this effect at plugin startup:
 * <pre>
 *   modifierManager.registerEffect("randomization", randomizationEffect);
 *   getServer().getPluginManager().registerEvents(randomizationEffect, this);
 * </pre>
 */
public class RandomizationEffect implements ModifierManager.ModifierEffect, Listener {

    /**
     * Pool of items that can appear as a randomized drop.
     * Intentionally varied (resources, food, mob-drops, rare items) to keep
     * every game interesting while avoiding obviously game-breaking items.
     */
    private static final Material[] DROP_POOL = {
            Material.COAL,             Material.RAW_IRON,         Material.RAW_GOLD,
            Material.IRON_INGOT,       Material.GOLD_INGOT,       Material.DIAMOND,
            Material.EMERALD,          Material.REDSTONE,         Material.LAPIS_LAZULI,
            Material.QUARTZ,           Material.AMETHYST_SHARD,   Material.COPPER_INGOT,
            Material.STICK,            Material.STRING,           Material.BONE,
            Material.ARROW,            Material.ROTTEN_FLESH,     Material.GUNPOWDER,
            Material.SPIDER_EYE,       Material.BLAZE_ROD,        Material.ENDER_PEARL,
            Material.SLIME_BALL,       Material.MAGMA_CREAM,      Material.BLAZE_POWDER,
            Material.BOOK,             Material.PAPER,            Material.FEATHER,
            Material.LEATHER,          Material.WHEAT,            Material.POTATO,
            Material.CARROT,           Material.BEETROOT,         Material.SUGAR_CANE,
            Material.OAK_LOG,          Material.SAND,             Material.GRAVEL,
            Material.FLINT,            Material.CLAY_BALL,        Material.BOWL,
            Material.NETHER_BRICK,     Material.CHORUS_FRUIT,     Material.HONEYCOMB,
            Material.PHANTOM_MEMBRANE, Material.NAUTILUS_SHELL,   Material.SCUTE,
    };

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

    /** Replaces every item a block would drop with a random item from the pool. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!activeWorlds.contains(event.getBlock().getWorld().getName().toLowerCase())) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (Item item : event.getItems()) {
            item.getItemStack().setType(randomMaterial(rng));
            item.getItemStack().setAmount(1);
        }
    }

    /** Replaces every item a mob would drop with a random item from the pool. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!activeWorlds.contains(event.getEntity().getWorld().getName().toLowerCase())) return;
        List<ItemStack> drops = event.getDrops();
        if (drops.isEmpty()) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (ItemStack stack : drops) {
            stack.setType(randomMaterial(rng));
            stack.setAmount(1);
        }
        event.setDroppedExp(0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Material randomMaterial(ThreadLocalRandom rng) {
        return DROP_POOL[rng.nextInt(DROP_POOL.length)];
    }
}
