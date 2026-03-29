package com.eternalworlds.portals.command;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.manager.ModifierManager;
import com.eternalworlds.portals.util.ColorUtil;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * /modifiers <name|list|clear>
 *
 * Lets administrators choose which game modifier applies to the current
 * dynamic-delay game round.
 *
 * Restrictions:
 *  - Sender must be a player with {@code eternalworlds.portal.admin}.
 *  - The player's current world must have an active dynamic-delay portal
 *    that is presently in the COUNTDOWN phase.
 *  - Exception: {@code /modifiers list} works from any world and location.
 *
 * The selected modifier is applied (its console commands are dispatched and
 * a chat announcement is sent to all players in the game world) the moment
 * the countdown finishes and the game transitions to GAME_RUNNING.
 * The selection is cleared automatically after it is applied or if the
 * countdown is interrupted (player count drops below 2).
 */
public class ModifiersCommand implements CommandExecutor, TabCompleter {

    private final EternalWorldsPlugin plugin;

    public ModifiersCommand(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ColorUtil.parse("&cЭта команда доступна только игрокам."));
            return true;
        }

        if (!player.hasPermission("eternalworlds.portal.admin")) {
            player.sendMessage(ColorUtil.parse("&cУ вас нет прав для использования этой команды."));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ColorUtil.parse("&eИспользование: &f/" + label + " <название|list|clear>"));
            return true;
        }

        String sub = args[0].toLowerCase();

        // /modifiers list is available from any location
        if (sub.equals("list")) {
            sendModifierList(player);
            return true;
        }

        // All other sub-commands require the player to be in a game world
        // that is currently counting down
        String worldName = player.getWorld().getName();
        String portalKey = plugin.getDynamicDelayManager().getPortalInCountdownForWorld(worldName);
        if (portalKey == null) {
            player.sendMessage(ColorUtil.parse(
                    "&cЭта команда доступна только в мире мини-игры во время отсчёта до начала игры."));
            return true;
        }

        if (sub.equals("clear") || sub.equals("none")) {
            plugin.getModifierManager().clearSelection(portalKey);
            broadcastToWorld(worldName, ColorUtil.parse("&7Модификатор для текущей игры &cотменён&7."));
            return true;
        }

        // Select a modifier by name
        ModifierManager.Modifier modifier = plugin.getModifierManager().getModifier(sub);
        if (modifier == null) {
            player.sendMessage(ColorUtil.parse("&cМодификатор &f" + args[0]
                    + " &cне найден. Используйте &f/" + label + " list &cдля просмотра списка."));
            return true;
        }

        plugin.getModifierManager().setSelection(portalKey, modifier.name());

        String announcement = ColorUtil.parse("&6Модификатор игры установлен: " + modifier.displayName());
        broadcastToWorld(worldName, announcement);
        if (!modifier.description().isEmpty()) {
            broadcastToWorld(worldName, ColorUtil.parse("&7" + modifier.description()));
        }
        return true;
    }

    private void sendModifierList(Player player) {
        Collection<ModifierManager.Modifier> all = plugin.getModifierManager().getModifiers();
        if (all.isEmpty()) {
            player.sendMessage(ColorUtil.parse("&7Нет зарегистрированных модификаторов."));
            return;
        }
        player.sendMessage(ColorUtil.parse("&6&lДоступные модификаторы:"));
        for (ModifierManager.Modifier m : all) {
            String line = "&e" + m.name() + " &8— " + m.displayName();
            if (!m.description().isEmpty()) line += " &7(" + m.description() + ")";
            player.sendMessage(ColorUtil.parse(line));
        }
    }

    private void broadcastToWorld(String worldName, String message) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) return;
        for (Player p : world.getPlayers()) p.sendMessage(message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("list");
            options.add("clear");
            for (ModifierManager.Modifier m : plugin.getModifierManager().getModifiers()) {
                options.add(m.name());
            }
            StringUtil.copyPartialMatches(args[0], options, completions);
        }
        return completions;
    }
}
