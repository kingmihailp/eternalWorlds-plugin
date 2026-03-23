package com.eternalworlds.portals.manager;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks per-player corner selections used when creating a portal.
 * The selection wand is a Blaze Rod with a specific display name.
 */
public class SelectionManager {

    public static final String WAND_NAME = "§6Portal Wand";
    public static final Material WAND_MATERIAL = Material.BLAZE_ROD;

    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public void setPos1(Player player, Location loc) {
        pos1.put(player.getUniqueId(), loc.clone());
        player.sendMessage("§a[Portals] §7Position §61 §7set to §f"
                + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
    }

    public void setPos2(Player player, Location loc) {
        pos2.put(player.getUniqueId(), loc.clone());
        player.sendMessage("§a[Portals] §7Position §62 §7set to §f"
                + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
    }

    public Location getPos1(Player player) { return pos1.get(player.getUniqueId()); }
    public Location getPos2(Player player) { return pos2.get(player.getUniqueId()); }

    public boolean hasSelection(Player player) {
        UUID id = player.getUniqueId();
        return pos1.containsKey(id) && pos2.containsKey(id);
    }

    public void clearSelection(Player player) {
        UUID id = player.getUniqueId();
        pos1.remove(id);
        pos2.remove(id);
    }

    /** Creates and returns a new Portal Wand item. */
    public static ItemStack createWand() {
        ItemStack wand = new ItemStack(WAND_MATERIAL);
        ItemMeta meta = wand.getItemMeta();
        meta.setDisplayName(WAND_NAME);
        wand.setItemMeta(meta);
        return wand;
    }

    /** Returns true if the item in the player's hand is the Portal Wand. */
    public static boolean isWand(ItemStack item) {
        if (item == null || item.getType() != WAND_MATERIAL) return false;
        if (!item.hasItemMeta()) return false;
        return WAND_NAME.equals(item.getItemMeta().getDisplayName());
    }
}
