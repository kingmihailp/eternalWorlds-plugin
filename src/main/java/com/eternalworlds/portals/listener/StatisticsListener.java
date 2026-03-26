package com.eternalworlds.portals.listener;

import com.eternalworlds.portals.EternalWorldsPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public class StatisticsListener implements Listener {

    private final EternalWorldsPlugin plugin;

    public StatisticsListener(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        String world  = victim.getWorld().getName();

        plugin.getStatisticsManager().recordDeath(world, victim.getUniqueId());

        Player killer = victim.getKiller();
        if (killer != null) {
            plugin.getStatisticsManager().recordKill(world, killer.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        String world = event.getBlock().getWorld().getName();
        plugin.getStatisticsManager().recordBlockPlaced(world, event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player)) return;
        String world = event.getEntity().getWorld().getName();
        plugin.getStatisticsManager().recordDamageDealt(world, attacker.getUniqueId(), event.getFinalDamage());
    }
}
