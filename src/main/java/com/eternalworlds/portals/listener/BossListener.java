package com.eternalworlds.portals.listener;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.manager.BossManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class BossListener implements Listener {

    private final EternalWorldsPlugin plugin;

    public BossListener(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Apply boss effects when the boss player enters a boss-game world. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player    = event.getPlayer();
        BossManager bm   = plugin.getBossManager();
        String newWorld  = player.getWorld().getName();
        String fromWorld = event.getFrom().getName();

        // Boss enters the boss world → apply effects + notify hunters
        BossManager.BossGame newGame = bm.getGame(newWorld);
        if (newGame != null
                && newGame.bossPlayerName().equalsIgnoreCase(player.getName())) {
            bm.applyBossEffects(player, newWorld);
            bm.notifyHunters(player);
        }

        // Boss leaves the boss world mid-game → treat as forfeit (end game)
        BossManager.BossGame oldGame = bm.getGame(fromWorld);
        if (oldGame != null
                && oldGame.bossUuid() != null
                && oldGame.bossUuid().equals(player.getUniqueId())) {
            bm.removeBossEffects(player);
            bm.onBossDeath(player); // hunters win by default when boss flees
        }
    }

    /** End the game when the boss dies; hunters are teleported out immediately. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossDeath(PlayerDeathEvent event) {
        Player dead = event.getPlayer();
        if (!plugin.getBossManager().isBossInActiveGame(dead)) return;
        plugin.getBossManager().onBossDeath(dead);
    }

    /**
     * Redirect the boss's respawn to the winners world instead of the normal
     * respawn point, so they don't land back in the (now-ended) boss world.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBossRespawn(PlayerRespawnEvent event) {
        String winnersWorldName = plugin.getBossManager()
                .pollRespawnWorld(event.getPlayer().getUniqueId());
        if (winnersWorldName == null) return;

        World w = plugin.getServer().getWorld(winnersWorldName);
        if (w != null) {
            event.setRespawnLocation(w.getSpawnLocation());
            event.getPlayer().sendMessage(
                    Component.text("Охотники победили! Вы проиграли как Босс.")
                            .color(NamedTextColor.RED).decorate(TextDecoration.BOLD));
        }
    }

    /**
     * If the boss disconnects during a game, treat it as a forfeit so hunters
     * are teleported to the winners world and the game state is cleaned up.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getBossManager().isBossInActiveGame(player)) return;
        plugin.getBossManager().removeBossEffects(player);
        plugin.getBossManager().onBossDeath(player);
    }
}
