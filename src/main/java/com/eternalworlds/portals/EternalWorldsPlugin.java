package com.eternalworlds.portals;

import com.eternalworlds.portals.command.ModifiersCommand;
import com.eternalworlds.portals.command.NpcCommand;
import com.eternalworlds.portals.command.PortalCommand;
import com.eternalworlds.portals.command.StatsCommand;
import com.eternalworlds.portals.listener.BossListener;
import com.eternalworlds.portals.listener.NpcListener;
import com.eternalworlds.portals.listener.PortalListener;
import com.eternalworlds.portals.listener.ProjectilesListener;
import com.eternalworlds.portals.listener.StatisticsListener;
import com.eternalworlds.portals.manager.AnvilRainEffect;
import com.eternalworlds.portals.manager.ChaosEffect;
import com.eternalworlds.portals.manager.EffectFeverEffect;
import com.eternalworlds.portals.manager.FairPlayEffect;
import com.eternalworlds.portals.manager.FloorIsLavaEffect;
import com.eternalworlds.portals.manager.MeteorRainEffect;
import com.eternalworlds.portals.manager.MobificationEffect;
import com.eternalworlds.portals.manager.OverflowEffect;
import com.eternalworlds.portals.manager.PushersEffect;
import com.eternalworlds.portals.manager.RandomizationEffect;
import com.eternalworlds.portals.manager.SwapperEffect;
import com.eternalworlds.portals.manager.BossManager;
import com.eternalworlds.portals.manager.DynamicDelayManager;
import com.eternalworlds.portals.manager.ItemRandomizationManager;
import com.eternalworlds.portals.manager.MinigameConfigManager;
import com.eternalworlds.portals.manager.ModifierManager;
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
    private ModifierManager          modifierManager;
    private AnvilRainEffect          anvilRainEffect;
    private ChaosEffect              chaosEffect;
    private EffectFeverEffect        effectFeverEffect;
    private FairPlayEffect           fairPlayEffect;
    private MeteorRainEffect         meteorRainEffect;
    private MobificationEffect       mobificationEffect;
    private OverflowEffect           overflowEffect;
    private PushersEffect            pushersEffect;
    private RandomizationEffect      randomizationEffect;
    private SwapperEffect            swapperEffect;
    private FloorIsLavaEffect        floorIsLavaEffect;
    private PortalSchedulerManager   portalSchedulerManager;
    private DynamicDelayManager      dynamicDelayManager;
    private PlayerFreezeManager      playerFreezeManager;
    private StatisticsManager        statisticsManager;
    private NpcManager               npcManager;
    private BossManager              bossManager;

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
        // Copy default modifiers.yml on first run, before ModifierManager loads it
        if (!new java.io.File(getDataFolder(), "modifiers.yml").exists()) {
            saveResource("modifiers.yml", false);
        }
        this.modifierManager           = new ModifierManager(this);
        // Register built-in modifier effects
        this.anvilRainEffect           = new AnvilRainEffect(this);
        modifierManager.registerEffect("anvil-rain", anvilRainEffect);
        this.randomizationEffect       = new RandomizationEffect(this);
        modifierManager.registerEffect("randomization", randomizationEffect);
        this.swapperEffect             = new SwapperEffect(this);
        modifierManager.registerEffect("swapper", swapperEffect);
        this.floorIsLavaEffect         = new FloorIsLavaEffect(this);
        modifierManager.registerEffect("floor-is-lava", floorIsLavaEffect);
        this.chaosEffect               = new ChaosEffect(this);
        modifierManager.registerEffect("chaos", chaosEffect);
        this.effectFeverEffect         = new EffectFeverEffect(this);
        modifierManager.registerEffect("effect-fever", effectFeverEffect);
        this.fairPlayEffect            = new FairPlayEffect(this);
        modifierManager.registerEffect("fair-play", fairPlayEffect);
        this.meteorRainEffect          = new MeteorRainEffect(this);
        modifierManager.registerEffect("meteor-rain", meteorRainEffect);
        this.mobificationEffect        = new MobificationEffect(this);
        modifierManager.registerEffect("mobification", mobificationEffect);
        this.overflowEffect            = new OverflowEffect(this);
        modifierManager.registerEffect("overflow", overflowEffect);
        this.pushersEffect             = new PushersEffect(this);
        modifierManager.registerEffect("pushers", pushersEffect);
        this.portalSchedulerManager    = new PortalSchedulerManager(this);
        this.playerFreezeManager       = new PlayerFreezeManager();
        this.dynamicDelayManager       = new DynamicDelayManager(this);
        this.statisticsManager         = new StatisticsManager(this);
        statisticsManager.load();
        this.npcManager                = new NpcManager(this);
        npcManager.load();
        this.bossManager               = new BossManager(this);
        portalManager.loadPortals();
        // Restore portal cycles that were active before the last shutdown
        portalSchedulerManager.loadAndRestartCycles();
        dynamicDelayManager.loadAndRestart();

        PortalCommand executor = new PortalCommand(this);
        var portalCmd = getCommand("portal");
        portalCmd.setExecutor(executor);
        portalCmd.setTabCompleter(executor);

        getServer().getPluginManager().registerEvents(new PortalListener(this), this);
        getServer().getPluginManager().registerEvents(playerFreezeManager, this);
        getServer().getPluginManager().registerEvents(new StatisticsListener(this), this);
        getServer().getPluginManager().registerEvents(new NpcListener(this), this);
        getServer().getPluginManager().registerEvents(new ProjectilesListener(this), this);
        getServer().getPluginManager().registerEvents(new BossListener(this), this);
        getServer().getPluginManager().registerEvents(randomizationEffect, this);
        getServer().getPluginManager().registerEvents(effectFeverEffect, this);

        NpcCommand npcExecutor = new NpcCommand(this);
        for (String cmd : new String[]{"npc", "spawnnpc", "removenpc", "npcattributes", "setnpcitems"}) {
            var c = getCommand(cmd);
            if (c != null) { c.setExecutor(npcExecutor); c.setTabCompleter(npcExecutor); }
        }
        npcManager.startScheduler();

        ModifiersCommand modifiersExecutor = new ModifiersCommand(this);
        var modCmd = getCommand("modifiers");
        if (modCmd != null) { modCmd.setExecutor(modifiersExecutor); modCmd.setTabCompleter(modifiersExecutor); }

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
        if (anvilRainEffect      != null) anvilRainEffect.stopAll();
        if (chaosEffect          != null) chaosEffect.stopAll();
        if (effectFeverEffect    != null) effectFeverEffect.stopAll();
        if (fairPlayEffect       != null) fairPlayEffect.stopAll();
        if (meteorRainEffect     != null) meteorRainEffect.stopAll();
        if (mobificationEffect   != null) mobificationEffect.stopAll();
        if (overflowEffect       != null) overflowEffect.stopAll();
        if (pushersEffect        != null) pushersEffect.stopAll();
        if (randomizationEffect  != null) randomizationEffect.stopAll();
        if (swapperEffect        != null) swapperEffect.stopAll();
        if (floorIsLavaEffect    != null) floorIsLavaEffect.stopAll();
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
    public ModifierManager           getModifierManager()           { return modifierManager; }
    public PortalSchedulerManager    getPortalSchedulerManager()    { return portalSchedulerManager; }
    public DynamicDelayManager       getDynamicDelayManager()       { return dynamicDelayManager; }
    public PlayerFreezeManager       getPlayerFreezeManager()       { return playerFreezeManager; }
    public StatisticsManager         getStatisticsManager()         { return statisticsManager; }
    public NpcManager                getNpcManager()                { return npcManager; }
    public BossManager               getBossManager()               { return bossManager; }
}
