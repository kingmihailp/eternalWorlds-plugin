package com.eternalworlds.portals.command;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.manager.NpcManager;
import com.eternalworlds.portals.model.NpcData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handles NPC management commands:
 *
 *   /spawnnpc <id>
 *   /removenpc <id>
 *   /npcattributes <id> <name|skin|command|lookatnearest> <value…>
 *   /setnpcitems <id> <mainhand|offhand|helmet|chestplate|leggings|boots>
 */
public class NpcCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ATTRIBUTES = Arrays.asList(
            "name", "skin", "command", "lookatnearest");
    private static final List<String> SLOTS = Arrays.asList(
            "mainhand", "offhand", "helmet", "chestplate", "leggings", "boots");
    private static final List<String> BOOL_VALUES = Arrays.asList("true", "false");

    private final EternalWorldsPlugin plugin;

    public NpcCommand(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Execution ──────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("eternalworlds.npc.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }
        return switch (command.getName().toLowerCase()) {
            case "spawnnpc"       -> cmdSpawnNpc(sender, args);
            case "removenpc"      -> cmdRemoveNpc(sender, args);
            case "npcattributes"  -> cmdNpcAttributes(sender, args);
            case "setnpcitems"    -> cmdSetNpcItems(sender, args);
            default -> false;
        };
    }

    // /spawnnpc <id>
    private boolean cmdSpawnNpc(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /spawnnpc <id>");
            return true;
        }
        String id = args[0].toLowerCase();
        if (plugin.getNpcManager().getNpc(id) != null) {
            sender.sendMessage("§cAn NPC with id §e" + id + " §calready exists.");
            return true;
        }
        plugin.getNpcManager().createNpc(id, player.getLocation());
        sender.sendMessage("§a[NPC] Created NPC §e" + id + "§a at your location.");
        sender.sendMessage("§7Use §f/npcattributes §7to set its name, skin and click command.");
        return true;
    }

    // /removenpc <id>
    private boolean cmdRemoveNpc(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage("§cUsage: /removenpc <id>");
            return true;
        }
        String id = args[0].toLowerCase();
        if (plugin.getNpcManager().removeNpc(id)) {
            sender.sendMessage("§a[NPC] Removed NPC §e" + id + "§a.");
        } else {
            sender.sendMessage("§cNPC §e" + id + " §cnot found.");
        }
        return true;
    }

    // /npcattributes <id> <attribute> <value…>
    private boolean cmdNpcAttributes(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /npcattributes <id> <name|skin|command|lookatnearest> <value>");
            sender.sendMessage("§7  name:         display name (hex: &#RRGGBB). Use \"none\" to remove.");
            sender.sendMessage("§7  skin:         skin key from skins.yml. Use \"none\" to clear.");
            sender.sendMessage("§7  command:      command run by clicking player ({player}, {npc}). Use \"none\" to clear.");
            sender.sendMessage("§7  lookatnearest: true|false");
            return true;
        }
        String id = args[0].toLowerCase();
        NpcData data = plugin.getNpcManager().getNpc(id);
        if (data == null) {
            sender.sendMessage("§cNPC §e" + id + " §cnot found.");
            return true;
        }

        String attribute = args[1].toLowerCase();
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));

        switch (attribute) {
            case "name" -> {
                String name = value.equalsIgnoreCase("none") ? null : value;
                plugin.getNpcManager().updateDisplayName(id, name);
                sender.sendMessage("§a[NPC] Name for §e" + id + "§a updated.");
            }
            case "skin" -> {
                if (value.equalsIgnoreCase("none")) {
                    plugin.getNpcManager().applySkin(id, null);
                    sender.sendMessage("§a[NPC] Skin cleared for §e" + id + "§a.");
                } else if (!plugin.getNpcManager().getSkins().containsKey(value)) {
                    sender.sendMessage("§cSkin §e" + value + " §cnot found in §fskins.yml§c.");
                    sender.sendMessage("§7Add it manually under §fskins.<name>.value §7and §fskins.<name>.signature§7.");
                } else {
                    plugin.getNpcManager().applySkin(id, value);
                    sender.sendMessage("§a[NPC] Skin §e" + value + "§a applied to §e" + id + "§a.");
                }
            }
            case "command" -> {
                String cmd = value.equalsIgnoreCase("none") ? null : value;
                data.setClickCommand(cmd);
                plugin.getNpcManager().save();
                sender.sendMessage("§a[NPC] Click command for §e" + id + "§a updated.");
            }
            case "lookatnearest" -> {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    sender.sendMessage("§cValue must be §etrue §cor §efalse§c.");
                    return true;
                }
                boolean look = Boolean.parseBoolean(value);
                data.setLookAtNearest(look);
                plugin.getNpcManager().save();
                sender.sendMessage("§a[NPC] Look-at-nearest for §e" + id
                        + (look ? " §aenabled." : " §cdisabled."));
            }
            default ->
                sender.sendMessage("§cUnknown attribute §e" + attribute
                        + "§c. Valid: name, skin, command, lookatnearest");
        }
        return true;
    }

    // /setnpcitems <id> <slot>  — takes item from player's main hand
    private boolean cmdSetNpcItems(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /setnpcitems <id> <mainhand|offhand|helmet|chestplate|leggings|boots>");
            return true;
        }
        String id   = args[0].toLowerCase();
        String slot = args[1].toLowerCase();

        if (plugin.getNpcManager().getNpc(id) == null) {
            sender.sendMessage("§cNPC §e" + id + " §cnot found.");
            return true;
        }
        if (!SLOTS.contains(slot)) {
            sender.sendMessage("§cInvalid slot §e" + slot + "§c. Valid: " + String.join(", ", SLOTS));
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        plugin.getNpcManager().applyEquipment(id, slot, item.getType().isAir() ? null : item);
        sender.sendMessage("§a[NPC] Slot §e" + slot + "§a for §e" + id + "§a updated.");
        return true;
    }

    // ── Tab completion ─────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("eternalworlds.npc.admin")) return List.of();
        List<String> completions = new ArrayList<>();
        List<String> npcIds = plugin.getNpcManager().getAllNpcs()
                .stream().map(NpcData::getId).toList();

        switch (command.getName().toLowerCase()) {
            case "removenpc" -> {
                if (args.length == 1) StringUtil.copyPartialMatches(args[0], npcIds, completions);
            }
            case "npcattributes" -> {
                if (args.length == 1) {
                    StringUtil.copyPartialMatches(args[0], npcIds, completions);
                } else if (args.length == 2) {
                    StringUtil.copyPartialMatches(args[1], ATTRIBUTES, completions);
                } else if (args.length == 3) {
                    String attr = args[1].toLowerCase();
                    if (attr.equals("lookatnearest")) {
                        StringUtil.copyPartialMatches(args[2], BOOL_VALUES, completions);
                    } else if (attr.equals("skin")) {
                        List<String> skinNames = new ArrayList<>(plugin.getNpcManager().getSkins().keySet());
                        StringUtil.copyPartialMatches(args[2], skinNames, completions);
                    }
                }
            }
            case "setnpcitems" -> {
                if (args.length == 1) StringUtil.copyPartialMatches(args[0], npcIds, completions);
                else if (args.length == 2) StringUtil.copyPartialMatches(args[1], SLOTS, completions);
            }
        }
        return completions;
    }
}
