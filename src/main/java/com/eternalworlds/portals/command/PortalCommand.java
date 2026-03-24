package com.eternalworlds.portals.command;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.manager.SelectionManager;
import com.eternalworlds.portals.manager.WorldConfigManager;
import com.eternalworlds.portals.model.Portal;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class PortalCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "create", "delete", "enable", "disable", "toggle",
            "list", "info", "wand", "setdest", "setspawn", "travel",
            "loadworld", "reload", "worldgamemode", "worldpvp", "worldclearinv"
    );

    private static final List<String> PVP_VALUES      = Arrays.asList("on", "off");
    private static final List<String> BOOL_VALUES     = Arrays.asList("true", "false");

    private static final List<String> GAMEMODE_VALUES = Arrays.asList(
            "survival", "creative", "adventure", "spectator", "none"
    );

    private final EternalWorldsPlugin plugin;

    public PortalCommand(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- Execution ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("eternalworlds.portal.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "create"    -> cmdCreate(sender, args);
            case "delete"    -> cmdDelete(sender, args);
            case "enable"    -> cmdSetEnabled(sender, args, true);
            case "disable"   -> cmdSetEnabled(sender, args, false);
            case "toggle"    -> cmdToggle(sender, args);
            case "list"      -> cmdList(sender);
            case "info"      -> cmdInfo(sender, args);
            case "wand"      -> cmdWand(sender);
            case "setdest"        -> cmdSetDest(sender, args);
            case "setspawn"       -> cmdSetSpawn(sender, args);
            case "travel"         -> cmdTravel(sender, args);
            case "loadworld"      -> cmdLoadWorld(sender, args);
            case "reload"         -> cmdReload(sender);
            case "worldgamemode"  -> cmdWorldGameMode(sender, args);
            case "worldpvp"       -> cmdWorldPvp(sender, args);
            case "worldclearinv"  -> cmdWorldClearInv(sender, args);
            default               -> { sendHelp(sender); yield true; }
        };
    }

    // /portal create <name> <destinationWorld>
    private boolean cmdCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can create portals.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage("§cUsage: /portal create <name> <destinationWorld>");
            return true;
        }
        String name    = args[1];
        String destWorld = args[2];

        if (plugin.getPortalManager().getPortal(name) != null) {
            player.sendMessage("§cA portal named §e" + name + " §calready exists.");
            return true;
        }
        if (!plugin.getSelectionManager().hasSelection(player)) {
            player.sendMessage("§cYou need to select two corners first. Use §e/portal wand §cto get the selection tool.");
            return true;
        }

        Location pos1 = plugin.getSelectionManager().getPos1(player);
        Location pos2 = plugin.getSelectionManager().getPos2(player);

        if (!pos1.getWorld().equals(pos2.getWorld())) {
            player.sendMessage("§cBoth positions must be in the same world.");
            return true;
        }

        // Destination: load the world now so we can use its spawn as the default destination
        World dest = plugin.getWorldManager().loadWorld(destWorld);
        Location destLoc = dest != null ? dest.getSpawnLocation() : new Location(null, 0, 64, 0);

        Portal portal = new Portal(
                name,
                pos1.getWorld().getName(),
                pos1.getBlockX(), pos1.getBlockY(), pos1.getBlockZ(),
                pos2.getBlockX(), pos2.getBlockY(), pos2.getBlockZ(),
                destWorld,
                destLoc.getX(), destLoc.getY(), destLoc.getZ(),
                destLoc.getYaw(), destLoc.getPitch(),
                true
        );

        plugin.getPortalManager().addPortal(portal);
        plugin.getSelectionManager().clearSelection(player);

        player.sendMessage("§a[Portals] Portal §e" + name + " §acreated! Destination: §b" + destWorld
                + " §7(" + (int) destLoc.getX() + ", " + (int) destLoc.getY() + ", " + (int) destLoc.getZ() + ")");
        player.sendMessage("§7Tip: use §e/portal setdest " + name + " §7to change the exact arrival point.");
        return true;
    }

    // /portal delete <name>
    private boolean cmdDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal delete <name>");
            return true;
        }
        String name = args[1];
        if (plugin.getPortalManager().removePortal(name)) {
            sender.sendMessage("§a[Portals] Portal §e" + name + " §adeleted.");
        } else {
            sender.sendMessage("§cPortal §e" + name + " §cnot found.");
        }
        return true;
    }

    // /portal enable|disable <name>
    private boolean cmdSetEnabled(CommandSender sender, String[] args, boolean enabled) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal " + (enabled ? "enable" : "disable") + " <name>");
            return true;
        }
        Portal p = plugin.getPortalManager().getPortal(args[1]);
        if (p == null) {
            sender.sendMessage("§cPortal §e" + args[1] + " §cnot found.");
            return true;
        }
        if (p.isEnabled() == enabled) {
            sender.sendMessage("§ePortal §b" + p.getName() + " §eis already " + (enabled ? "enabled" : "disabled") + ".");
            return true;
        }
        p.setEnabled(enabled);
        plugin.getPortalManager().savePortals();
        sender.sendMessage("§a[Portals] Portal §e" + p.getName() + (enabled ? " §aenabled." : " §cdisabled."));
        return true;
    }

    // /portal toggle <name>
    private boolean cmdToggle(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal toggle <name>");
            return true;
        }
        Portal p = plugin.getPortalManager().getPortal(args[1]);
        if (p == null) {
            sender.sendMessage("§cPortal §e" + args[1] + " §cnot found.");
            return true;
        }
        p.setEnabled(!p.isEnabled());
        plugin.getPortalManager().savePortals();
        sender.sendMessage("§a[Portals] Portal §e" + p.getName()
                + (p.isEnabled() ? " §aenabled." : " §cdisabled."));
        return true;
    }

    // /portal list
    private boolean cmdList(CommandSender sender) {
        Collection<Portal> all = plugin.getPortalManager().getAllPortals();
        if (all.isEmpty()) {
            sender.sendMessage("§7No portals defined yet.");
            return true;
        }
        sender.sendMessage("§6=== Portals (" + all.size() + ") ===");
        for (Portal p : all) {
            String status = p.isEnabled() ? "§aON" : "§cOFF";
            sender.sendMessage(" §e" + p.getName() + " §7[" + status + "§7] "
                    + p.getSourceWorld() + " §7→ §b" + p.getDestinationWorld());
        }
        return true;
    }

    // /portal info <name>
    private boolean cmdInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal info <name>");
            return true;
        }
        Portal p = plugin.getPortalManager().getPortal(args[1]);
        if (p == null) {
            sender.sendMessage("§cPortal §e" + args[1] + " §cnot found.");
            return true;
        }
        String status = p.isEnabled() ? "§aEnabled" : "§cDisabled";
        sender.sendMessage("§6=== Portal: " + p.getName() + " ===");
        sender.sendMessage(" §7Status:      " + status);
        sender.sendMessage(" §7Source world: §f" + p.getSourceWorld());
        sender.sendMessage(" §7Region:       §f("
                + p.getMinX() + ", " + p.getMinY() + ", " + p.getMinZ() + ") §7to §f("
                + p.getMaxX() + ", " + p.getMaxY() + ", " + p.getMaxZ() + ")");
        sender.sendMessage(" §7Dest world:   §b" + p.getDestinationWorld());
        sender.sendMessage(" §7Dest pos:     §f"
                + String.format("%.2f, %.2f, %.2f", p.getDestX(), p.getDestY(), p.getDestZ()));
        return true;
    }

    // /portal wand
    private boolean cmdWand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can receive the wand.");
            return true;
        }
        player.getInventory().addItem(SelectionManager.createWand());
        player.sendMessage("§a[Portals] You received the §6Portal Wand§a.");
        player.sendMessage("§7 §f Left-click §7a block to set §6Pos 1");
        player.sendMessage("§7 §f Right-click §7a block to set §6Pos 2");
        return true;
    }

    // /portal setdest <name>   — sets destination to the player's current location
    private boolean cmdSetDest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can set the destination.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("§cUsage: /portal setdest <name>");
            return true;
        }
        Portal p = plugin.getPortalManager().getPortal(args[1]);
        if (p == null) {
            player.sendMessage("§cPortal §e" + args[1] + " §cnot found.");
            return true;
        }
        // Validate that the player is in the destination world (or allow from anywhere)
        Location loc = player.getLocation();
        if (!loc.getWorld().getName().equals(p.getDestinationWorld())) {
            player.sendMessage("§cYou must be in world §e" + p.getDestinationWorld()
                    + " §cto set the destination.");
            return true;
        }
        p.setDestination(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        plugin.getPortalManager().savePortals();
        player.sendMessage("§a[Portals] Destination for §e" + p.getName() + " §aset to your location.");
        return true;
    }

    // /portal setspawn [worldName]
    //   No arg  → sets spawn for the player's current world at their location.
    //   With arg → player must be standing in that world; sets its spawn.
    private boolean cmdSetSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can set a world spawn.");
            return true;
        }

        String worldName = args.length >= 2 ? args[1] : player.getWorld().getName();

        if (!player.getWorld().getName().equalsIgnoreCase(worldName)) {
            player.sendMessage("§cYou must be standing in world §e" + worldName
                    + " §cto set its spawn.");
            return true;
        }

        plugin.getWorldConfigManager().setSpawn(worldName, player.getLocation());
        player.sendMessage("§a[Portals] Spawn for world §e" + worldName
                + " §aset to your location §7("
                + (int) player.getLocation().getX() + ", "
                + (int) player.getLocation().getY() + ", "
                + (int) player.getLocation().getZ() + ")§7.");
        return true;
    }

    // /portal travel <worldName>
    private boolean cmdTravel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use /portal travel.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("§cUsage: /portal travel <worldName>");
            return true;
        }
        String worldName = args[1];

        if (player.getWorld().getName().equalsIgnoreCase(worldName)) {
            player.sendMessage("§eYou are already in world §b" + worldName + "§e.");
            return true;
        }

        player.sendMessage("§7Travelling to §e" + worldName + "§7...");
        World world = plugin.getWorldManager().loadWorld(worldName);
        if (world == null) {
            player.sendMessage("§c[Portals] World §e" + worldName + " §ccould not be loaded.");
            return true;
        }

        // Use custom spawn if configured, otherwise fall back to world spawn
        WorldConfigManager.WorldSpawn customSpawn = plugin.getWorldConfigManager().getSpawn(worldName);
        Location destination = customSpawn != null
                ? customSpawn.toLocation(world)
                : world.getSpawnLocation();

        player.teleportAsync(destination).thenAccept(success -> {
            if (success && plugin.getConfig().getBoolean("teleport-message", true)) {
                String msg = plugin.getConfig()
                        .getString("teleport-message-text", "&aYou have been teleported to &b{world}&a!")
                        .replace("{world}", worldName)
                        .replace("&", "§");
                player.sendMessage(msg);
            }
        });
        return true;
    }

    // /portal worldclearinv <worldName> <true|false>
    private boolean cmdWorldClearInv(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal worldclearinv <worldName> <true|false>");
            return true;
        }
        String worldName = args[1];
        String value     = args[2].toLowerCase();

        if (!value.equals("true") && !value.equals("false")) {
            sender.sendMessage("§cInvalid value: §e" + value + "§c. Use §ftrue §cor §ffalse§c.");
            return true;
        }

        boolean clear = value.equals("true");
        plugin.getWorldConfigManager().setClearInventory(worldName, clear);
        sender.sendMessage("§a[Portals] Clear inventory on entry for world §e" + worldName
                + (clear ? " §aenabled." : " §cdisabled."));
        return true;
    }

    // /portal loadworld <worldName>
    private boolean cmdLoadWorld(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal loadworld <worldName>");
            List<String> unloaded = plugin.getWorldManager().listUnloadedWorlds();
            if (!unloaded.isEmpty()) {
                sender.sendMessage("§7Available: §f" + String.join(", ", unloaded));
            } else {
                sender.sendMessage("§7No unloaded worlds found in the world container.");
            }
            return true;
        }
        String worldName = args[1];
        sender.sendMessage("§7Loading world §e" + worldName + "§7...");
        World w = plugin.getWorldManager().loadWorld(worldName);
        if (w != null) {
            sender.sendMessage("§a[Portals] World §e" + worldName + " §aloaded successfully.");
        } else {
            sender.sendMessage("§cFailed to load world §e" + worldName + "§c. Check the server logs.");
        }
        return true;
    }

    // /portal worldgamemode <worldName> <survival|creative|adventure|spectator|none>
    private boolean cmdWorldGameMode(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal worldgamemode <worldName> <survival|creative|adventure|spectator|none>");
            return true;
        }
        String worldName = args[1];
        String modeStr   = args[2].toLowerCase();

        if (modeStr.equals("none")) {
            plugin.getWorldConfigManager().removeGameMode(worldName);
            sender.sendMessage("§a[Portals] Removed default gamemode for world §e" + worldName + "§a.");
            return true;
        }

        GameMode gm;
        try {
            gm = GameMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cUnknown gamemode: §e" + modeStr);
            sender.sendMessage("§7Valid: §fsurvival, creative, adventure, spectator, none");
            return true;
        }

        plugin.getWorldConfigManager().setGameMode(worldName, gm);
        sender.sendMessage("§a[Portals] Default gamemode for world §e" + worldName
                + " §aset to §b" + gm.name() + "§a.");
        return true;
    }

    // /portal worldpvp <worldName> <on|off>
    private boolean cmdWorldPvp(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal worldpvp <worldName> <on|off>");
            return true;
        }
        String worldName = args[1];
        String value     = args[2].toLowerCase();

        if (!value.equals("on") && !value.equals("off")) {
            sender.sendMessage("§cInvalid value: §e" + value + "§c. Use §fon §cor §foff§c.");
            return true;
        }

        boolean pvpOn = value.equals("on");
        plugin.getWorldConfigManager().setPvp(worldName, pvpOn);
        sender.sendMessage("§a[Portals] PvP for world §e" + worldName
                + (pvpOn ? " §aenabled." : " §cdisabled."));
        return true;
    }

    // /portal reload
    private boolean cmdReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getPortalManager().loadPortals();
        plugin.getWorldConfigManager().load();
        sender.sendMessage("§a[Portals] Configuration and portals reloaded.");
        return true;
    }

    // ---- Help ----

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== EternalWorlds Portals ===");
        sender.sendMessage("§e/portal wand §7– Get the selection wand (Left/Right click corners)");
        sender.sendMessage("§e/portal create <name> <destWorld> §7– Create a portal from your selection");
        sender.sendMessage("§e/portal delete <name> §7– Delete a portal");
        sender.sendMessage("§e/portal enable <name> §7– Enable a portal");
        sender.sendMessage("§e/portal disable <name> §7– Disable a portal");
        sender.sendMessage("§e/portal toggle <name> §7– Toggle a portal on/off");
        sender.sendMessage("§e/portal setdest <name> §7– Set arrival point to your location");
        sender.sendMessage("§e/portal setspawn [world] §7– Set spawn point for a world (defaults to current world)");
        sender.sendMessage("§e/portal travel <world> §7– Teleport yourself to a world");
        sender.sendMessage("§e/portal list §7– List all portals");
        sender.sendMessage("§e/portal info <name> §7– Show portal details");
        sender.sendMessage("§e/portal loadworld <worldName> §7– Load a world from the server folder");
        sender.sendMessage("§e/portal worldgamemode <world> <mode> §7– Set default gamemode for a world (none to remove)");
        sender.sendMessage("§e/portal worldpvp <world> <on|off> §7– Enable or disable PvP in a world");
        sender.sendMessage("§e/portal worldclearinv <world> <true|false> §7– Clear inventory on entry to a world");
        sender.sendMessage("§e/portal reload §7– Reload config and portals");
    }

    // ---- Tab completion ----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("eternalworlds.portal.admin")) return List.of();

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, completions);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            List<String> portalNames = plugin.getPortalManager().getAllPortals()
                    .stream().map(Portal::getName).toList();
            switch (sub) {
                case "delete", "info", "toggle", "setdest" ->
                        StringUtil.copyPartialMatches(args[1], portalNames, completions);
                case "setspawn" -> {
                    List<String> loaded = plugin.getWorldManager().listLoadedWorlds();
                    StringUtil.copyPartialMatches(args[1], loaded, completions);
                }
                case "enable" -> {
                    List<String> disabled = plugin.getPortalManager().getAllPortals()
                            .stream().filter(p -> !p.isEnabled()).map(Portal::getName).toList();
                    StringUtil.copyPartialMatches(args[1], disabled, completions);
                }
                case "disable" -> {
                    List<String> enabled = plugin.getPortalManager().getAllPortals()
                            .stream().filter(Portal::isEnabled).map(Portal::getName).toList();
                    StringUtil.copyPartialMatches(args[1], enabled, completions);
                }
                case "loadworld" -> {
                    List<String> worlds = plugin.getWorldManager().listUnloadedWorlds();
                    StringUtil.copyPartialMatches(args[1], worlds, completions);
                }
                case "travel", "worldgamemode", "worldpvp", "worldclearinv" -> {
                    List<String> allWorlds = new ArrayList<>(plugin.getWorldManager().listLoadedWorlds());
                    allWorlds.addAll(plugin.getWorldManager().listUnloadedWorlds());
                    StringUtil.copyPartialMatches(args[1], allWorlds, completions);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("worldgamemode")) {
            StringUtil.copyPartialMatches(args[2], GAMEMODE_VALUES, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("worldpvp")) {
            StringUtil.copyPartialMatches(args[2], PVP_VALUES, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("worldclearinv")) {
            StringUtil.copyPartialMatches(args[2], BOOL_VALUES, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            // destination world: suggest loaded + unloaded worlds
            List<String> all = new ArrayList<>(plugin.getWorldManager().listLoadedWorlds());
            all.addAll(plugin.getWorldManager().listUnloadedWorlds());
            StringUtil.copyPartialMatches(args[2], all, completions);
        }

        return completions;
    }
}
