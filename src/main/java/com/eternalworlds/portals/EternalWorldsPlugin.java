package com.eternalworlds.portals;

import com.eternalworlds.portals.command.PortalCommand;
import com.eternalworlds.portals.listener.PortalListener;
import com.eternalworlds.portals.manager.ItemRandomizationManager;
import com.eternalworlds.portals.manager.MinigameConfigManager;
import com.eternalworlds.portals.manager.PortalManager;
import com.eternalworlds.portals.manager.PortalSchedulerManager;
import com.eternalworlds.portals.manager.RandomPointManager;
import com.eternalworlds.portals.manager.SelectionManager;
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
        portalManager.loadPortals();
        // Restore portal cycles that were active before the last shutdown
        portalSchedulerManager.loadAndRestartCycles();

        PortalCommand executor = new PortalCommand(this);
        var cmd = getCommand("portal");
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);

        getServer().getPluginManager().registerEvents(new PortalListener(this), this);

        getLogger().info("EternalWorlds Portals v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        portalSchedulerManager.cancelAll();
        itemRandomizationManager.cancelAll();
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
}
