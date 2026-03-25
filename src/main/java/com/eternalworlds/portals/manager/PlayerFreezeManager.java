package com.eternalworlds.portals.manager;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Prevents frozen players from moving or attacking.
 * Head rotation (yaw/pitch) is still allowed.
 */
public class PlayerFreezeManager implements Listener {

    private final Set<UUID> frozen = new HashSet<>();

    /** Adds a player to the frozen set. */
    public void freeze(Player player) {
        frozen.add(player.getUniqueId());
    }

    /** Removes a player from the frozen set. */
    public void unfreeze(Player player) {
        frozen.remove(player.getUniqueId());
    }

    /** Removes all players from the frozen set. */
    public void unfreezeAll() {
        frozen.clear();
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.contains(uuid);
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    /** Block positional movement but allow head rotation. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!frozen.contains(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to   = event.getTo();
        if (to == null) return;

        if (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ()) {
            // Reset position, preserve look direction so it doesn't feel jarring
            Location cancel = from.clone();
            cancel.setYaw(to.getYaw());
            cancel.setPitch(to.getPitch());
            event.setTo(cancel);
        }
    }

    /** Block attacks (melee and projectile) from frozen players. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker != null && frozen.contains(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
