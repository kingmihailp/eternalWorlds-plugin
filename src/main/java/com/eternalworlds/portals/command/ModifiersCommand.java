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
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * /modifiers <name|list|clear>
 *
 * Lets players in a game world vote for a modifier during the COUNTDOWN phase.
 * The modifier with the most votes is applied when the game starts.
 *
 * Restrictions:
 *  - /modifiers list   works from any world.
 *  - /modifiers <name> and /modifiers clear require the player to be in a world
 *    whose portal is currently in the COUNTDOWN phase.
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

        if (args.length == 0) {
            player.sendMessage(ColorUtil.parse("&eИспользование: &f/" + label + " <название|list|clear>"));
            return true;
        }

        String sub = args[0].toLowerCase();

        if (!player.hasPermission("eternalworlds.portal.admin")) {
            player.sendMessage(ColorUtil.parse("&cУ вас нет прав для использования этой команды."));
            return true;
        }

        // /modifiers list — available from any location
        if (sub.equals("list")) {
            sendModifierList(player);
            return true;
        }

        // Admin voting commands require the player to be in a countdown world
        String worldName = player.getWorld().getName();
        String portalKey = plugin.getDynamicDelayManager().getPortalInCountdownForWorld(worldName);
        if (portalKey == null) {
            player.sendMessage(ColorUtil.parse(
                    "&cЭта команда доступна только в мире мини-игры во время отсчёта до начала игры."));
            return true;
        }

        if (sub.equals("clear") || sub.equals("none")) {
            boolean had = plugin.getModifierManager().clearVote(portalKey, player.getUniqueId());
            if (had) {
                player.sendMessage(ColorUtil.parse("&7Ваш голос &cотменён&7."));
                broadcastVoteCounts(worldName, portalKey);
            } else {
                player.sendMessage(ColorUtil.parse("&7Вы ещё не голосовали за модификатор."));
            }
            return true;
        }

        // Vote for a modifier by name
        ModifierManager.Modifier modifier = plugin.getModifierManager().getModifier(sub);
        if (modifier == null) {
            player.sendMessage(ColorUtil.parse("&cМодификатор &f" + args[0]
                    + " &cне найден. Используйте &f/" + label + " list &cдля просмотра."));
            return true;
        }

        String prevVote = plugin.getModifierManager().castVote(portalKey, player.getUniqueId(), modifier.name());
        if (prevVote == null) {
            player.sendMessage(ColorUtil.parse("&aВы проголосовали за: " + modifier.displayName() + "&a!"));
        } else {
            ModifierManager.Modifier prev = plugin.getModifierManager().getModifier(prevVote);
            String prevDisplay = prev != null ? prev.displayName() : prevVote;
            player.sendMessage(ColorUtil.parse(
                    "&eВаш голос изменён: " + prevDisplay + " &e→ " + modifier.displayName() + "&e!"));
        }

        broadcastVoteCounts(worldName, portalKey);
        return true;
    }

    /** Shows available modifiers with current vote counts (if in a countdown world). */
    private void sendModifierList(Player player) {
        Collection<ModifierManager.Modifier> all = plugin.getModifierManager().getModifiers();
        if (all.isEmpty()) {
            player.sendMessage(ColorUtil.parse("&7Нет зарегистрированных модификаторов."));
            return;
        }

        String worldName = player.getWorld().getName();
        String portalKey = plugin.getDynamicDelayManager().getPortalInCountdownForWorld(worldName);
        Map<String, Integer> counts = portalKey != null
                ? plugin.getModifierManager().getVoteCounts(portalKey)
                : Collections.emptyMap();
        String myVote = portalKey != null
                ? plugin.getModifierManager().getPlayerVote(portalKey, player.getUniqueId())
                : null;

        player.sendMessage(ColorUtil.parse("&6&lДоступные модификаторы:"));
        for (ModifierManager.Modifier m : all) {
            int voteCount = counts.getOrDefault(m.name(), 0);
            String votesStr = voteCount > 0 ? " &e[" + voteCount + " гол.]" : "";
            String myMark   = m.name().equals(myVote) ? " &a✔" : "";
            String line = "&f" + m.name() + votesStr + myMark + " &8— " + m.displayName();
            if (!m.description().isEmpty()) line += " &7(" + m.description() + ")";
            player.sendMessage(ColorUtil.parse(line));
        }
    }

    /** Sends current vote standings to all players in the world. */
    private void broadcastVoteCounts(String worldName, String portalKey) {
        Map<String, Integer> counts = plugin.getModifierManager().getVoteCounts(portalKey);
        World world = plugin.getServer().getWorld(worldName);
        if (world == null || counts.isEmpty()) return;

        StringBuilder sb = new StringBuilder("&8Голосование: ");
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            ModifierManager.Modifier m = plugin.getModifierManager().getModifier(e.getKey());
            String dn = m != null ? m.displayName() : e.getKey();
            if (!first) sb.append("&8, ");
            sb.append(dn).append(" &8— &f").append(e.getValue()).append(" &8гол.");
            first = false;
        }
        String msg = ColorUtil.parse(sb.toString());
        for (Player p : world.getPlayers()) p.sendMessage(msg);
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
