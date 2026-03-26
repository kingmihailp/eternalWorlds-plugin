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
            "loadworld", "reload",
            "worldgamemode", "worldpvp", "worldclearinv",
            "worlditemrandomization", "seteliminationylevel",
            "setportaldelay", "stopportaldelay",
            "setrandomplayerpoint", "clearrandompoints",
            "setwinnersdest", "setmessage", "setcleaningworld",
            "setworldleavable", "setportaldynamicdelay", "startdynamicdelay",
            "stopdynamicdelay", "removedynamicdelay",
            "allownetherperworld", "allowendperworld",
            "setbuildingheightperworld", "allowbedsleeping",
            "setcleaningminy",
            "createstatisticcounter", "removestatisticcounter", "setstatistictracking"
    );

    private static final List<String> STAT_METRICS = Arrays.asList(
            "kills", "deaths", "wins", "blocks-placed", "damage-dealt"
    );

    private static final List<String> MESSAGE_TYPES = Arrays.asList("open", "close", "end");

    private static final List<String> PVP_VALUES  = Arrays.asList("on", "off");
    private static final List<String> BOOL_VALUES  = Arrays.asList("true", "false");
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
            case "worldgamemode"         -> cmdWorldGameMode(sender, args);
            case "worldpvp"              -> cmdWorldPvp(sender, args);
            case "worldclearinv"         -> cmdWorldClearInv(sender, args);
            case "worlditemrandomization"-> cmdWorldItemRandomization(sender, args);
            case "seteliminationylevel"  -> cmdSetEliminationYLevel(sender, args);
            case "setportaldelay"        -> cmdSetPortalDelay(sender, args);
            case "stopportaldelay"       -> cmdStopPortalDelay(sender, args);
            case "setrandomplayerpoint"  -> cmdSetRandomPlayerPoint(sender, args);
            case "clearrandompoints"     -> cmdClearRandomPoints(sender, args);
            case "setwinnersdest"        -> cmdSetWinnersDest(sender, args);
            case "setmessage"            -> cmdSetMessage(sender, args);
            case "setcleaningworld"      -> cmdSetCleaningWorld(sender, args);
            case "setworldleavable"      -> cmdSetWorldLeavable(sender, args);
            case "setportaldynamicdelay" -> cmdSetPortalDynamicDelay(sender, args);
            case "startdynamicdelay"     -> cmdStartDynamicDelay(sender, args);
            case "stopdynamicdelay"      -> cmdStopDynamicDelay(sender, args);
            case "removedynamicdelay"    -> cmdRemoveDynamicDelay(sender, args);
            case "allownetherperworld"      -> cmdAllowNetherPerWorld(sender, args);
            case "allowendperworld"         -> cmdAllowEndPerWorld(sender, args);
            case "setbuildingheightperworld"-> cmdSetBuildingHeightPerWorld(sender, args);
            case "allowbedsleeping"         -> cmdAllowBedSleeping(sender, args);
            case "setcleaningminy"          -> cmdSetCleaningMinY(sender, args);
            case "createstatisticcounter"   -> cmdCreateStatisticCounter(sender, args);
            case "removestatisticcounter"   -> cmdRemoveStatisticCounter(sender, args);
            case "setstatistictracking"     -> cmdSetStatisticTracking(sender, args);
            default                         -> { sendHelp(sender); yield true; }
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
        // If disabling a portal that has an active dynamic delay cycle, stop the cycle —
        // otherwise the cycle will re-enable the portal on the next phase transition.
        // The config is preserved; use /portal startdynamicdelay to restart.
        if (!enabled && plugin.getDynamicDelayManager().isActive(p.getName())) {
            plugin.getDynamicDelayManager().stopDynamic(p.getName());
            sender.sendMessage("§7[Portals] Dynamic delay cycle for §e" + p.getName() + " §7was also stopped (config preserved).");
        }
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

    // /portal setportaldelay <portalName> <enableSeconds> <disableSeconds>
    private boolean cmdSetPortalDelay(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /portal setportaldelay <portalName> <enableSeconds> <disableSeconds>");
            return true;
        }
        String portalName = args[1];
        if (plugin.getPortalManager().getPortal(portalName) == null) {
            sender.sendMessage("§cPortal §e" + portalName + " §cnot found.");
            return true;
        }
        int enableSec, disableSec;
        try {
            enableSec  = Integer.parseInt(args[2]);
            disableSec = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cSeconds must be whole numbers.");
            return true;
        }
        if (enableSec <= 0 || disableSec <= 0) {
            sender.sendMessage("§cSeconds must be greater than zero.");
            return true;
        }
        plugin.getPortalSchedulerManager().startCycle(portalName, enableSec, disableSec);
        sender.sendMessage("§a[Portals] Portal §e" + portalName
                + " §acycle started: §b" + enableSec + "s §aopen, §c" + disableSec + "s §aclosed.");
        return true;
    }

    // /portal stopportaldelay <portalName>
    private boolean cmdStopPortalDelay(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal stopportaldelay <portalName>");
            return true;
        }
        String portalName = args[1];
        if (!plugin.getPortalSchedulerManager().isRunning(portalName)) {
            sender.sendMessage("§ePortal §b" + portalName + " §ehas no active cycle.");
            return true;
        }
        plugin.getPortalSchedulerManager().stopCycle(portalName);
        sender.sendMessage("§a[Portals] Cycle stopped for portal §e" + portalName + "§a.");
        return true;
    }

    // /portal worlditemrandomization <worldName> <on|off>
    private boolean cmdWorldItemRandomization(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal worlditemrandomization <worldName> <on|off>");
            return true;
        }
        String worldName = args[1];
        String value     = args[2].toLowerCase();
        if (!value.equals("on") && !value.equals("off")) {
            sender.sendMessage("§cUse §fon §cor §foff§c.");
            return true;
        }
        boolean enable = value.equals("on");
        plugin.getWorldConfigManager().setItemRandomizationEnabled(worldName, enable);
        if (enable) {
            plugin.getItemRandomizationManager().startRandomization(worldName);
            sender.sendMessage("§a[Portals] Item randomization §aenabled §afor world §e" + worldName + "§a.");
        } else {
            plugin.getItemRandomizationManager().stopRandomization(worldName);
            sender.sendMessage("§a[Portals] Item randomization §cdisabled §afor world §e" + worldName + "§a.");
        }
        return true;
    }

    // /portal setrandomplayerpoint <portalName>
    private boolean cmdSetRandomPlayerPoint(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can set random player points.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("§cUsage: /portal setrandomplayerpoint <portalName>");
            return true;
        }
        String portalName = args[1];
        if (plugin.getPortalManager().getPortal(portalName) == null) {
            player.sendMessage("§cPortal §e" + portalName + " §cnot found.");
            return true;
        }
        plugin.getRandomPointManager().addPoint(portalName, player.getLocation());
        int total = plugin.getRandomPointManager().getPointCount(portalName);
        player.sendMessage("§a[Portals] Random point #" + total + " added for portal §e" + portalName
                + " §7(" + (int) player.getLocation().getX()
                + ", " + (int) player.getLocation().getY()
                + ", " + (int) player.getLocation().getZ() + ")§7.");
        return true;
    }

    // /portal clearrandompoints <portalName>
    private boolean cmdClearRandomPoints(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal clearrandompoints <portalName>");
            return true;
        }
        String portalName = args[1];
        plugin.getRandomPointManager().clearPoints(portalName);
        sender.sendMessage("§a[Portals] All random points cleared for portal §e" + portalName + "§a.");
        return true;
    }

    // /portal seteliminationylevel <worldName> <yLevel> <targetWorld>
    private boolean cmdSetEliminationYLevel(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /portal seteliminationylevel <worldName> <yLevel> <targetWorld>");
            return true;
        }
        String worldName   = args[1];
        String targetWorld = args[3];
        int yLevel;
        try {
            yLevel = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cY level must be a whole number.");
            return true;
        }
        plugin.getWorldConfigManager().setEliminationConfig(worldName, yLevel, targetWorld);
        sender.sendMessage("§a[Portals] Elimination set for world §e" + worldName
                + "§a: Y ≤ §b" + yLevel + " §a→ teleport to §e" + targetWorld + "§a.");
        return true;
    }

    // /portal setwinnersdest <portalName> <worldName>
    private boolean cmdSetWinnersDest(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal setwinnersdest <portalName> <worldName>");
            return true;
        }
        String portalName = args[1];
        String worldName  = args[2];
        if (plugin.getPortalManager().getPortal(portalName) == null) {
            sender.sendMessage("§cPortal §e" + portalName + " §cnot found.");
            return true;
        }
        plugin.getMinigameConfigManager().setWinnersDest(portalName, worldName);
        sender.sendMessage("§a[Portals] Winners destination for portal §e" + portalName
                + " §aset to world §b" + worldName + "§a.");
        return true;
    }

    // /portal setmessage <portalName> <open|close|end> <message...>
    //   Supports: &a, &b, &#RRGGBB, #RRGGBB  — stored as-is, parsed on broadcast.
    //   Placeholders: {portal}, {world}
    private boolean cmdSetMessage(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /portal setmessage <portalName> <open|close|end> <message...>");
            sender.sendMessage("§7Supports &-codes, &#RRGGBB hex and #RRGGBB hex. Placeholders: {portal}, {world}");
            return true;
        }
        String portalName = args[1];
        String type       = args[2].toLowerCase();
        if (plugin.getPortalManager().getPortal(portalName) == null) {
            sender.sendMessage("§cPortal §e" + portalName + " §cnot found.");
            return true;
        }
        if (!MESSAGE_TYPES.contains(type)) {
            sender.sendMessage("§cUnknown message type: §e" + type + "§c. Use §fopen§c, §fclose §cor §fend§c.");
            return true;
        }
        // Join remaining args as the message (preserves spaces)
        String message = String.join(" ", Arrays.copyOfRange(args, 3, args.length));

        switch (type) {
            case "open"  -> plugin.getMinigameConfigManager().setOpenMessage(portalName, message);
            case "close" -> plugin.getMinigameConfigManager().setCloseMessage(portalName, message);
            case "end"   -> plugin.getMinigameConfigManager().setEndMessage(portalName, message);
        }
        sender.sendMessage("§a[Portals] " + type.substring(0, 1).toUpperCase() + type.substring(1)
                + " message for portal §e" + portalName + " §aset.");
        return true;
    }

    // /portal setcleaningworld <worldName> <true|false>
    private boolean cmdSetCleaningWorld(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal setcleaningworld <worldName> <true|false>");
            sender.sendMessage("§7When true, the world is cleaned (entities + blocks within 800 blocks)");
            sender.sendMessage("§7at the moment winners are teleported out.");
            return true;
        }
        String worldName = args[1];
        String value     = args[2].toLowerCase();
        if (!value.equals("true") && !value.equals("false")) {
            sender.sendMessage("§cUse §ftrue §cor §ffalse§c.");
            return true;
        }
        boolean clean = value.equals("true");
        plugin.getWorldConfigManager().setCleaningEnabled(worldName, clean);
        sender.sendMessage("§a[Portals] Cleaning mode for world §e" + worldName
                + (clean ? " §aenabled." : " §cdisabled.")
                + (clean ? " §7(entities + blocks will be cleared on game end)" : ""));
        return true;
    }

    // /portal setcleaningminy <worldName> <y|reset>
    private boolean cmdSetCleaningMinY(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal setcleaningminy <worldName> <y|reset>");
            sender.sendMessage("§7Sets the minimum Y from which block cleaning starts.");
            sender.sendMessage("§7Blocks below this Y are never removed. Use §freset §7for the default (world bottom + 5).");
            return true;
        }
        String worldName = args[1];
        String value     = args[2];
        if (value.equalsIgnoreCase("reset")) {
            plugin.getWorldConfigManager().removeCleanMinY(worldName);
            sender.sendMessage("§a[Portals] Clean min-Y for world §e" + worldName + " §areset to default.");
            return true;
        }
        try {
            int y = Integer.parseInt(value);
            plugin.getWorldConfigManager().setCleanMinY(worldName, y);
            sender.sendMessage("§a[Portals] Clean min-Y for world §e" + worldName + " §eset to §c" + y
                    + "§a. Blocks below Y=" + y + " will never be removed.");
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid number: §f" + value + "§c. Use an integer or §freset§c.");
        }
        return true;
    }

    // /portal setworldleavable <sourceWorld> <targetWorld>
    private boolean cmdSetWorldLeavable(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal setworldleavable <sourceWorld> <targetWorld>");
            return true;
        }
        String source = args[1];
        String target = args[2];
        plugin.getWorldConfigManager().setLeavable(source, target);
        sender.sendMessage("§a[Portals] Players leaving world §e" + source
                + " §awill be teleported to §b" + target + "§a.");
        return true;
    }

    // /portal setportaldynamicdelay <portalName> <countdownSeconds> <gameSeconds> <winnersWorld>
    private boolean cmdSetPortalDynamicDelay(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage("§cUsage: /portal setportaldynamicdelay <portalName> <countdownSeconds> <gameSeconds> <winnersWorld>");
            sender.sendMessage("§7The portal stays open until 2+ players are in the destination world.");
            sender.sendMessage("§7A countdown of <countdownSeconds> runs before the portal closes for <gameSeconds>.");
            sender.sendMessage("§7If 1 player survives early, they win immediately. Winners are sent to <winnersWorld>.");
            return true;
        }
        String portalName   = args[1];
        String winnersWorld = args[4];

        if (plugin.getPortalManager().getPortal(portalName) == null) {
            sender.sendMessage("§cPortal §e" + portalName + " §cnot found.");
            return true;
        }

        int countdownSec;
        try {
            countdownSec = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cCountdown duration must be a whole number of seconds.");
            return true;
        }
        if (countdownSec <= 0) {
            sender.sendMessage("§cCountdown duration must be greater than zero.");
            return true;
        }

        int gameSec;
        try {
            gameSec = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cGame duration must be a whole number of seconds.");
            return true;
        }
        if (gameSec <= 0) {
            sender.sendMessage("§cGame duration must be greater than zero.");
            return true;
        }

        plugin.getDynamicDelayManager().saveConfig(portalName, countdownSec, gameSec, winnersWorld);
        sender.sendMessage("§a[Portals] Dynamic delay config saved for portal §e" + portalName
                + "§a: §b" + countdownSec + "s §acountdown, §b" + gameSec + "s §agame time, winners → §b" + winnersWorld + "§a.");
        sender.sendMessage("§7Use §f/portal startdynamicdelay " + portalName + " §7to activate the cycle.");
        return true;
    }

    // /portal startdynamicdelay <portalName>
    private boolean cmdStartDynamicDelay(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal startdynamicdelay <portalName>");
            return true;
        }
        String portalName = args[1];
        if (!plugin.getDynamicDelayManager().hasDynamic(portalName)) {
            sender.sendMessage("§cPortal §e" + portalName + " §chas no dynamic delay config. Use §f/portal setportaldynamicdelay §cfirst.");
            return true;
        }
        if (plugin.getDynamicDelayManager().isActive(portalName)) {
            sender.sendMessage("§ePortal §b" + portalName + " §ealready has an active dynamic delay cycle.");
            return true;
        }
        plugin.getDynamicDelayManager().startDynamic(portalName);
        sender.sendMessage("§a[Portals] Dynamic delay cycle started for portal §e" + portalName + "§a.");
        return true;
    }

    // /portal stopdynamicdelay <portalName>
    private boolean cmdStopDynamicDelay(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal stopdynamicdelay <portalName>");
            return true;
        }
        String portalName = args[1];
        if (!plugin.getDynamicDelayManager().isActive(portalName)) {
            sender.sendMessage("§cPortal §e" + portalName + " §cdoes not have an active dynamic delay cycle.");
            return true;
        }
        plugin.getDynamicDelayManager().stopDynamic(portalName);
        sender.sendMessage("§a[Portals] Dynamic delay cycle stopped for portal §e" + portalName
                + "§a. Config is preserved — use §f/portal startdynamicdelay §ato restart.");
        return true;
    }

    // /portal removedynamicdelay <portalName>
    private boolean cmdRemoveDynamicDelay(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal removedynamicdelay <portalName>");
            return true;
        }
        String portalName = args[1];
        if (!plugin.getDynamicDelayManager().hasDynamic(portalName)) {
            sender.sendMessage("§cPortal §e" + portalName + " §chas no dynamic delay config.");
            return true;
        }
        plugin.getDynamicDelayManager().removeConfig(portalName);
        sender.sendMessage("§a[Portals] Dynamic delay config removed for portal §e" + portalName + "§a.");
        return true;
    }

    // /portal allownetherperworld <worldName> <true|false>
    private boolean cmdAllowNetherPerWorld(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal allownetherperworld <worldName> <true|false>");
            return true;
        }
        String worldName = args[1];
        String value     = args[2].toLowerCase();
        if (!value.equals("true") && !value.equals("false")) {
            sender.sendMessage("§cInvalid value: §e" + value + "§c. Use §ftrue §cor §ffalse§c.");
            return true;
        }
        boolean allowed = value.equals("true");
        plugin.getWorldConfigManager().setNetherAllowed(worldName, allowed);
        sender.sendMessage("§a[Portals] Nether portals in world §e" + worldName
                + (allowed ? " §aenabled." : " §cdisabled."));
        return true;
    }

    // /portal allowendperworld <worldName> <true|false>
    private boolean cmdAllowEndPerWorld(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal allowendperworld <worldName> <true|false>");
            return true;
        }
        String worldName = args[1];
        String value     = args[2].toLowerCase();
        if (!value.equals("true") && !value.equals("false")) {
            sender.sendMessage("§cInvalid value: §e" + value + "§c. Use §ftrue §cor §ffalse§c.");
            return true;
        }
        boolean allowed = value.equals("true");
        plugin.getWorldConfigManager().setEndAllowed(worldName, allowed);
        sender.sendMessage("§a[Portals] End portals in world §e" + worldName
                + (allowed ? " §aenabled." : " §cdisabled."));
        return true;
    }

    // /portal allowbedsleeping <worldName> <true|false>
    private boolean cmdAllowBedSleeping(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal allowbedsleeping <worldName> <true|false>");
            return true;
        }
        String worldName = args[1];
        String value     = args[2].toLowerCase();
        if (!value.equals("true") && !value.equals("false")) {
            sender.sendMessage("§cInvalid value: §e" + value + "§c. Use §ftrue §cor §ffalse§c.");
            return true;
        }
        boolean allowed = value.equals("true");
        plugin.getWorldConfigManager().setBedSleepingAllowed(worldName, allowed);
        sender.sendMessage("§a[Portals] Bed sleeping in world §e" + worldName
                + (allowed ? " §aenabled." : " §cdisabled."));
        return true;
    }

    // /portal setbuildingheightperworld <worldName> <maxY|remove>
    private boolean cmdSetBuildingHeightPerWorld(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal setbuildingheightperworld <worldName> <maxY|remove>");
            return true;
        }
        String worldName = args[1];
        String value     = args[2].toLowerCase();

        if (value.equals("remove")) {
            plugin.getWorldConfigManager().removeBuildingHeight(worldName);
            sender.sendMessage("§a[Portals] Building height limit removed for world §e" + worldName + "§a.");
            return true;
        }

        int maxY;
        try {
            maxY = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid value: §e" + value + "§c. Provide an integer Y level or §fremove§c.");
            return true;
        }

        plugin.getWorldConfigManager().setBuildingHeight(worldName, maxY);
        sender.sendMessage("§a[Portals] Building height in world §e" + worldName
                + " §alimited to §bY=" + maxY + "§a.");
        return true;
    }

    // /portal reload
    private boolean cmdReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getPortalManager().loadPortals();
        plugin.getWorldConfigManager().load();
        plugin.getMinigameConfigManager().load();
        sender.sendMessage("§a[Portals] Configuration and portals reloaded.");
        return true;
    }

    // /portal createstatisticcounter <name> <world>
    private boolean cmdCreateStatisticCounter(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /portal createstatisticcounter <name> <world>");
            return true;
        }
        String name  = args[1];
        String world = args[2];
        if (plugin.getStatisticsManager().getCounter(name) != null) {
            sender.sendMessage("§cA statistics counter named §e" + name + " §calready exists.");
            return true;
        }
        plugin.getStatisticsManager().createCounter(name, world);
        sender.sendMessage("§a[Portals] Statistics counter §e" + name
                + " §acreated for world §b" + world + "§a.");
        sender.sendMessage("§7Use §f/portal setstatistictracking " + name + " <metric> <true|false> §7to toggle tracking.");
        return true;
    }

    // /portal removestatisticcounter <name>
    private boolean cmdRemoveStatisticCounter(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /portal removestatisticcounter <name>");
            return true;
        }
        if (plugin.getStatisticsManager().removeCounter(args[1])) {
            sender.sendMessage("§a[Portals] Statistics counter §e" + args[1] + " §aremoved.");
        } else {
            sender.sendMessage("§cStatistics counter §e" + args[1] + " §cnot found.");
        }
        return true;
    }

    // /portal setstatistictracking <counter> <metric> <true|false>
    private boolean cmdSetStatisticTracking(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /portal setstatistictracking <counter> <kills|deaths|wins|blocks-placed|damage-dealt> <true|false>");
            return true;
        }
        String counterName = args[1];
        String metric      = args[2].toLowerCase();
        String valueStr    = args[3].toLowerCase();
        if (!valueStr.equals("true") && !valueStr.equals("false")) {
            sender.sendMessage("§cInvalid value: §e" + valueStr + "§c. Use §ftrue §cor §ffalse§c.");
            return true;
        }
        if (!STAT_METRICS.contains(metric)) {
            sender.sendMessage("§cInvalid metric: §e" + metric + "§c. Valid: §f" + String.join(", ", STAT_METRICS));
            return true;
        }
        boolean enabled = valueStr.equals("true");
        if (!plugin.getStatisticsManager().setTracking(counterName, metric, enabled)) {
            sender.sendMessage("§cStatistics counter §e" + counterName + " §cnot found.");
            return true;
        }
        sender.sendMessage("§a[Portals] Tracking §e" + metric + (enabled ? " §aenabled" : " §cdisabled")
                + " §afor counter §e" + counterName + "§a.");
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
        sender.sendMessage("§e/portal worlditemrandomization <world> <on|off> §7– Give random items every 4s in a world");
        sender.sendMessage("§e/portal setportaldelay <portal> <openSec> <closeSec> §7– Repeating open/close cycle for a portal");
        sender.sendMessage("§e/portal stopportaldelay <portal> §7– Stop the portal open/close cycle");
        sender.sendMessage("§e/portal setrandomplayerpoint <portal> §7– Add your position as a random spawn point for a portal");
        sender.sendMessage("§e/portal clearrandompoints <portal> §7– Remove all random spawn points for a portal");
        sender.sendMessage("§e/portal seteliminationylevel <world> <y> <targetWorld> §7– Teleport players below Y to another world");
        sender.sendMessage("§e/portal setwinnersdest <portal> <world> §7– Set world where players are sent after the game ends");
        sender.sendMessage("§e/portal setmessage <portal> <open|close|end> <msg> §7– Set a portal message (hex: &#RRGGBB, {portal}, {world})");
        sender.sendMessage("§e/portal setcleaningworld <world> <true|false> §7– Clean a world (entities+blocks, r=800) when game ends");
        sender.sendMessage("§e/portal setcleaningminy <world> <y|reset> §7– Set minimum Y for cleaning (blocks below this Y are never removed)");
        sender.sendMessage("§e/portal setworldleavable <sourceWorld> <targetWorld> §7– Teleport players to targetWorld whenever they leave sourceWorld");
        sender.sendMessage("§e/portal setportaldynamicdelay <portal> <cdSec> <gameSec> <winnersWorld> §7– Save dynamic delay config (does not start cycle)");
        sender.sendMessage("§e/portal startdynamicdelay <portal> §7– Start the dynamic delay cycle (config must exist)");
        sender.sendMessage("§e/portal stopdynamicdelay <portal> §7– Stop the cycle (config is preserved)");
        sender.sendMessage("§e/portal removedynamicdelay <portal> §7– Delete the dynamic delay config and stop the cycle");
        sender.sendMessage("§e/portal allownetherperworld <world> <true|false> §7– Allow or block vanilla nether portals in a world");
        sender.sendMessage("§e/portal allowendperworld <world> <true|false> §7– Allow or block vanilla end portals/gateways in a world");
        sender.sendMessage("§e/portal allowbedsleeping <world> <true|false> §7– Allow or block bed sleeping (spawn point setting) in a world");
        sender.sendMessage("§e/portal setbuildingheightperworld <world> <maxY|remove> §7– Limit block placement above Y in a world (remove to clear)");
        sender.sendMessage("§e/portal createstatisticcounter <name> <world> §7– Create a statistics counter for a world");
        sender.sendMessage("§e/portal removestatisticcounter <name> §7– Delete a statistics counter and its data");
        sender.sendMessage("§e/portal setstatistictracking <counter> <metric> <true|false> §7– Enable or disable a tracking metric (kills/deaths/wins/blocks-placed/damage-dealt)");
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
                case "travel", "worldgamemode", "worldpvp", "worldclearinv",
                        "worlditemrandomization", "seteliminationylevel", "setcleaningworld",
                        "allownetherperworld", "allowendperworld",
                        "setbuildingheightperworld", "allowbedsleeping",
                        "setcleaningminy" -> {
                    List<String> allWorlds = new ArrayList<>(plugin.getWorldManager().listLoadedWorlds());
                    allWorlds.addAll(plugin.getWorldManager().listUnloadedWorlds());
                    StringUtil.copyPartialMatches(args[1], allWorlds, completions);
                }
                case "setportaldelay", "stopportaldelay",
                        "setrandomplayerpoint", "clearrandompoints",
                        "setwinnersdest", "setmessage",
                        "setportaldynamicdelay" -> {
                    List<String> portalNames2 = plugin.getPortalManager().getAllPortals()
                            .stream().map(Portal::getName).toList();
                    StringUtil.copyPartialMatches(args[1], portalNames2, completions);
                }
                case "startdynamicdelay" -> {
                    // Portals with config but no active cycle
                    List<String> startable = plugin.getPortalManager().getAllPortals()
                            .stream().map(Portal::getName)
                            .filter(n -> plugin.getDynamicDelayManager().hasDynamic(n)
                                    && !plugin.getDynamicDelayManager().isActive(n))
                            .toList();
                    StringUtil.copyPartialMatches(args[1], startable, completions);
                }
                case "stopdynamicdelay" -> {
                    // Only portals with an active cycle
                    List<String> active = plugin.getPortalManager().getAllPortals()
                            .stream().map(Portal::getName)
                            .filter(n -> plugin.getDynamicDelayManager().isActive(n))
                            .toList();
                    StringUtil.copyPartialMatches(args[1], active, completions);
                }
                case "removedynamicdelay" -> {
                    // All portals with a config
                    List<String> configured = plugin.getPortalManager().getAllPortals()
                            .stream().map(Portal::getName)
                            .filter(n -> plugin.getDynamicDelayManager().hasDynamic(n))
                            .toList();
                    StringUtil.copyPartialMatches(args[1], configured, completions);
                }
                case "setworldleavable" -> {
                    List<String> allWorlds = new ArrayList<>(plugin.getWorldManager().listLoadedWorlds());
                    allWorlds.addAll(plugin.getWorldManager().listUnloadedWorlds());
                    StringUtil.copyPartialMatches(args[1], allWorlds, completions);
                }
                case "createstatisticcounter" -> {
                    // arg1 = counter name, no suggestions
                }
                case "removestatisticcounter" -> {
                    List<String> counterNames = plugin.getStatisticsManager().getAllCounters()
                            .stream().map(c -> c.name()).toList();
                    StringUtil.copyPartialMatches(args[1], counterNames, completions);
                }
                case "setstatistictracking" -> {
                    List<String> counterNames = plugin.getStatisticsManager().getAllCounters()
                            .stream().map(c -> c.name()).toList();
                    StringUtil.copyPartialMatches(args[1], counterNames, completions);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("worldgamemode")) {
            StringUtil.copyPartialMatches(args[2], GAMEMODE_VALUES, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("worldpvp")) {
            StringUtil.copyPartialMatches(args[2], PVP_VALUES, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("worldclearinv")) {
            StringUtil.copyPartialMatches(args[2], BOOL_VALUES, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("allownetherperworld")) {
            StringUtil.copyPartialMatches(args[2], BOOL_VALUES, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("allowendperworld")) {
            StringUtil.copyPartialMatches(args[2], BOOL_VALUES, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("allowbedsleeping")) {
            StringUtil.copyPartialMatches(args[2], BOOL_VALUES, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setbuildingheightperworld")) {
            StringUtil.copyPartialMatches(args[2], List.of("remove"), completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setcleaningminy")) {
            StringUtil.copyPartialMatches(args[2], List.of("reset"), completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("worlditemrandomization")) {
            StringUtil.copyPartialMatches(args[2], PVP_VALUES, completions);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("seteliminationylevel")) {
            List<String> allWorlds = new ArrayList<>(plugin.getWorldManager().listLoadedWorlds());
            allWorlds.addAll(plugin.getWorldManager().listUnloadedWorlds());
            StringUtil.copyPartialMatches(args[3], allWorlds, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setwinnersdest")) {
            List<String> allWorlds = new ArrayList<>(plugin.getWorldManager().listLoadedWorlds());
            allWorlds.addAll(plugin.getWorldManager().listUnloadedWorlds());
            StringUtil.copyPartialMatches(args[2], allWorlds, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setmessage")) {
            StringUtil.copyPartialMatches(args[2], MESSAGE_TYPES, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            // destination world: suggest loaded + unloaded worlds
            List<String> all = new ArrayList<>(plugin.getWorldManager().listLoadedWorlds());
            all.addAll(plugin.getWorldManager().listUnloadedWorlds());
            StringUtil.copyPartialMatches(args[2], all, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setworldleavable")) {
            List<String> allWorlds = new ArrayList<>(plugin.getWorldManager().listLoadedWorlds());
            allWorlds.addAll(plugin.getWorldManager().listUnloadedWorlds());
            StringUtil.copyPartialMatches(args[2], allWorlds, completions);
        } else if (args.length == 5 && args[0].equalsIgnoreCase("setportaldynamicdelay")) {
            List<String> allWorlds = new ArrayList<>(plugin.getWorldManager().listLoadedWorlds());
            allWorlds.addAll(plugin.getWorldManager().listUnloadedWorlds());
            StringUtil.copyPartialMatches(args[4], allWorlds, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("createstatisticcounter")) {
            List<String> allWorlds = new ArrayList<>(plugin.getWorldManager().listLoadedWorlds());
            allWorlds.addAll(plugin.getWorldManager().listUnloadedWorlds());
            StringUtil.copyPartialMatches(args[2], allWorlds, completions);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setstatistictracking")) {
            StringUtil.copyPartialMatches(args[2], STAT_METRICS, completions);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("setstatistictracking")) {
            StringUtil.copyPartialMatches(args[3], BOOL_VALUES, completions);
        }

        return completions;
    }
}
