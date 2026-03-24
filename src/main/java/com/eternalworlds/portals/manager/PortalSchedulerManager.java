package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.model.Portal;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages repeating enable/disable cycles for portals.
 *
 * Cycle:
 *   1. Enable portal for <enableSeconds>
 *   2. Disable portal, start item randomization in dest world (if configured)
 *   3. Wait <disableSeconds>, then repeat from step 1.
 */
public class PortalSchedulerManager {

    private final EternalWorldsPlugin plugin;
    /** portal name (lower-case) -> pending phase task */
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public PortalSchedulerManager(EternalWorldsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts (or restarts) the cycle for the given portal.
     * Immediately enables the portal and schedules the first phase switch.
     */
    public void startCycle(String portalName, int enableSeconds, int disableSeconds) {
        stopCycle(portalName);

        Portal portal = plugin.getPortalManager().getPortal(portalName);
        if (portal == null) return;

        String destWorld = portal.getDestinationWorld();

        // Phase 1 start: enable portal, stop item randomization
        setPortalEnabled(portalName, true);
        plugin.getItemRandomizationManager().stopRandomization(destWorld);

        scheduleNextPhase(portalName, destWorld, enableSeconds, disableSeconds, true);
    }

    /** Stops the cycle and leaves the portal in whatever state it's currently in. */
    public void stopCycle(String portalName) {
        BukkitTask t = tasks.remove(portalName.toLowerCase());
        if (t != null) t.cancel();
    }

    public boolean isRunning(String portalName) {
        return tasks.containsKey(portalName.toLowerCase());
    }

    public void cancelAll() {
        tasks.values().forEach(BukkitTask::cancel);
        tasks.clear();
    }

    // ---- Internal ----

    private void scheduleNextPhase(String portalName, String destWorld,
                                   int enableSec, int disableSec, boolean portalCurrentlyEnabled) {
        int delayTicks = (portalCurrentlyEnabled ? enableSec : disableSec) * 20;

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (portalCurrentlyEnabled) {
                // Enable phase ended → disable portal, start randomization
                setPortalEnabled(portalName, false);
                if (plugin.getWorldConfigManager().isItemRandomizationEnabled(destWorld)) {
                    plugin.getItemRandomizationManager().startRandomization(destWorld);
                }
                scheduleNextPhase(portalName, destWorld, enableSec, disableSec, false);
            } else {
                // Disable phase ended → enable portal, stop randomization
                setPortalEnabled(portalName, true);
                plugin.getItemRandomizationManager().stopRandomization(destWorld);
                scheduleNextPhase(portalName, destWorld, enableSec, disableSec, true);
            }
        }, delayTicks);

        tasks.put(portalName.toLowerCase(), task);
    }

    private void setPortalEnabled(String portalName, boolean enabled) {
        Portal portal = plugin.getPortalManager().getPortal(portalName);
        if (portal != null && portal.isEnabled() != enabled) {
            portal.setEnabled(enabled);
            plugin.getPortalManager().savePortals();
        }
    }
}
