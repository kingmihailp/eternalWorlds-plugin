package com.eternalworlds.portals.command;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.manager.StatisticsManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;

public class StatsCommand implements CommandExecutor, TabCompleter {

    private final EternalWorldsPlugin plugin;

    public StatsCommand(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage("§cUsage: /stats <counter-name>");
            return true;
        }

        String counterName = args[0];
        List<String> lines = plugin.getStatisticsManager().formatStats(counterName, player.getUniqueId());
        if (lines == null) {
            player.sendMessage("§cStatistics counter §e" + counterName + " §cnot found.");
            return true;
        }

        for (String line : lines) {
            player.sendMessage(line);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> names = plugin.getStatisticsManager().getAllCounters()
                    .stream().map(StatisticsManager.StatCounter::name).toList();
            StringUtil.copyPartialMatches(args[0], names, completions);
        }
        return completions;
    }
}
