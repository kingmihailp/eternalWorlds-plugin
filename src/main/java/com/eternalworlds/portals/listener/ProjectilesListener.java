package com.eternalworlds.portals.listener;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public class ProjectilesListener implements Listener {

    /** Knockback strength applied to hit entities (horizontal). */
    private static final double KNOCKBACK_HORIZONTAL = 0.6;
    /** Upward component of knockback. */
    private static final double KNOCKBACK_VERTICAL   = 0.25;
    /** Fireball throw cooldown in ticks (20 ticks = 1 second). */
    private static final int FIREBALL_COOLDOWN_TICKS = 20;

    private final EternalWorldsPlugin plugin;

    public ProjectilesListener(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Applies knockback when an egg, snowball or fireball hits a living entity
     * in a world where projectiles features are enabled.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile proj = event.getEntity();
        if (!(proj instanceof Egg || proj instanceof Snowball || proj instanceof Fireball)) return;

        if (event.getHitEntity() == null) return;
        if (!(event.getHitEntity() instanceof LivingEntity target)) return;

        if (!plugin.getWorldConfigManager()
                .isProjectilesFeaturesEnabled(proj.getWorld().getName())) return;

        // Direction from projectile to target (horizontal only for cleaner feel)
        double dx = target.getLocation().getX() - proj.getLocation().getX();
        double dz = target.getLocation().getZ() - proj.getLocation().getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.001) { dx /= len; dz /= len; }

        Vector knockback = new Vector(dx * KNOCKBACK_HORIZONTAL,
                                      KNOCKBACK_VERTICAL,
                                      dz * KNOCKBACK_HORIZONTAL);
        target.setVelocity(target.getVelocity().add(knockback));
    }

    /**
     * Allows players to throw a fireball by right-clicking with a Fire Charge
     * in worlds where projectiles features are enabled.
     * Consumes one Fire Charge and applies a 1-second cooldown.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFireChargeUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.FIRE_CHARGE) return;

        if (!plugin.getWorldConfigManager()
                .isProjectilesFeaturesEnabled(player.getWorld().getName())) return;

        // Check cooldown (Bukkit item cooldown)
        if (player.hasCooldown(Material.FIRE_CHARGE)) return;

        event.setCancelled(true);

        // Consume one Fire Charge
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }

        // Launch fireball in the direction the player is looking
        Fireball fireball = player.launchProjectile(Fireball.class);
        fireball.setShooter(player);
        fireball.setDirection(player.getEyeLocation().getDirection());
        fireball.setIsIncendiary(true);
        fireball.setYield(1.5f);

        player.setCooldown(Material.FIRE_CHARGE, FIREBALL_COOLDOWN_TICKS);
    }
}
