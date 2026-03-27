package com.eternalworlds.portals;

import com.eternalworlds.portals.command.NpcCommand;
import com.eternalworlds.portals.command.PortalCommand;
import com.eternalworlds.portals.command.StatsCommand;
import com.eternalworlds.portals.listener.NpcListener;
import com.eternalworlds.portals.listener.PortalListener;
import com.eternalworlds.portals.listener.StatisticsListener;
import com.eternalworlds.portals.manager.DynamicDelayManager;
import com.eternalworlds.portals.manager.ItemRandomizationManager;
import com.eternalworlds.portals.manager.MinigameConfigManager;
import com.eternalworlds.portals.manager.NpcManager;
import com.eternalworlds.portals.manager.PlayerFreezeManager;
import com.eternalworlds.portals.manager.PortalManager;
import com.eternalworlds.portals.manager.PortalSchedulerManager;
import com.eternalworlds.portals.manager.RandomPointManager;
import com.eternalworlds.portals.manager.SelectionManager;
import com.eternalworlds.portals.manager.StatisticsManager;
import com.eternalworlds.portals.manager.WorldConfigManager;
import com.eternalworlds.portals.manager.WorldManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class EternalWorldsPlugin extends JavaPlugin {

    private PortalManager            portalManager;
    private WorldManager             worldManager;
    private SelectionManager         selectionManager;
    private WorldConfigManager       worldConfigManager;
    private ItemRandomizationManager itemRandomizationManager;
    private RandomPointManager       randomPointManager;
    private MinigameConfigManager    minigameConfigManager;
    private PortalSchedulerManager   portalSchedulerManager;
    private DynamicDelayManager      dynamicDelayManager;
    private PlayerFreezeManager      playerFreezeManager;
    private StatisticsManager        statisticsManager;
    private NpcManager               npcManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.worldManager              = new WorldManager(this);
        this.selectionManager          = new SelectionManager();
        this.worldConfigManager        = new WorldConfigManager(this);
        this.portalManager             = new PortalManager(this);
        this.itemRandomizationManager  = new ItemRandomizationManager(this);
        this.randomPointManager        = new RandomPointManager(this);
        this.minigameConfigManager     = new MinigameConfigManager(this);
        this.portalSchedulerManager    = new PortalSchedulerManager(this);
        this.playerFreezeManager       = new PlayerFreezeManager();
        this.dynamicDelayManager       = new DynamicDelayManager(this);
        this.statisticsManager         = new StatisticsManager(this);
        statisticsManager.load();
        this.npcManager                = new NpcManager(this);
        npcManager.load();
        portalManager.loadPortals();
        // Restore portal cycles that were active before the last shutdown
        portalSchedulerManager.loadAndRestartCycles();
        dynamicDelayManager.loadAndRestart();

        PortalCommand executor = new PortalCommand(this);
        var cmd = getCommand("portal");
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);

        getServer().getPluginManager().registerEvents(new PortalListener(this), this);
        getServer().getPluginManager().registerEvents(playerFreezeManager, this);
        getServer().getPluginManager().registerEvents(new StatisticsListener(this), this);
        getServer().getPluginManager().registerEvents(new NpcListener(this), this);

        NpcCommand npcExecutor = new NpcCommand(this);
        for (String cmd : new String[]{"spawnnpc", "removenpc", "npcattributes", "setnpcitems"}) {
            var c = getCommand(cmd);
            if (c != null) { c.setExecutor(npcExecutor); c.setTabCompleter(npcExecutor); }
        }
        npcManager.startScheduler();

        StatsCommand statsExecutor = new StatsCommand(this);
        var statsCmd = getCommand("stats");
        statsCmd.setExecutor(statsExecutor);
        statsCmd.setTabCompleter(statsExecutor);

        // Flush dirty statistics data every 5 minutes
        getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> statisticsManager.flushIfDirty(), 6000L, 6000L);

        getLogger().info("EternalWorlds Portals v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (dynamicDelayManager != null) dynamicDelayManager.cancelAll();
        portalSchedulerManager.cancelAll();
        itemRandomizationManager.cancelAll();
        if (statisticsManager != null) statisticsManager.flushIfDirty();
        if (npcManager != null) npcManager.despawnAll();
        if (portalManager != null) portalManager.savePortals();
        getLogger().info("EternalWorlds Portals disabled.");
    }

    public PortalManager             getPortalManager()             { return portalManager; }
    public WorldManager              getWorldManager()              { return worldManager; }
    public SelectionManager          getSelectionManager()          { return selectionManager; }
    public WorldConfigManager        getWorldConfigManager()        { return worldConfigManager; }
    public ItemRandomizationManager  getItemRandomizationManager()  { return itemRandomizationManager; }
    public RandomPointManager        getRandomPointManager()        { return randomPointManager; }
    public MinigameConfigManager     getMinigameConfigManager()     { return minigameConfigManager; }
    public PortalSchedulerManager    getPortalSchedulerManager()    { return portalSchedulerManager; }
    public DynamicDelayManager       getDynamicDelayManager()       { return dynamicDelayManager; }
    public PlayerFreezeManager       getPlayerFreezeManager()       { return playerFreezeManager; }
    public StatisticsManager         getStatisticsManager()         { return statisticsManager; }
    public NpcManager                getNpcManager()                { return npcManager; }
}
