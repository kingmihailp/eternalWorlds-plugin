package com.eternalworlds.portals;

import com.eternalworlds.portals.command.PortalCommand;
import com.eternalworlds.portals.listener.PortalListener;
import com.eternalworlds.portals.manager.PortalManager;
import com.eternalworlds.portals.manager.SelectionManager;
import com.eternalworlds.portals.manager.WorldManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class EternalWorldsPlugin extends JavaPlugin {

    private PortalManager portalManager;
    private WorldManager worldManager;
    private SelectionManager selectionManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.worldManager    = new WorldManager(this);
        this.selectionManager = new SelectionManager();
        this.portalManager   = new PortalManager(this);
        portalManager.loadPortals();

        PortalCommand executor = new PortalCommand(this);
        var cmd = getCommand("portal");
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);

        getServer().getPluginManager().registerEvents(new PortalListener(this), this);

        getLogger().info("EternalWorlds Portals v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (portalManager != null) {
            portalManager.savePortals();
        }
        getLogger().info("EternalWorlds Portals disabled.");
    }

    public PortalManager    getPortalManager()    { return portalManager; }
    public WorldManager     getWorldManager()     { return worldManager; }
    public SelectionManager getSelectionManager() { return selectionManager; }
}
