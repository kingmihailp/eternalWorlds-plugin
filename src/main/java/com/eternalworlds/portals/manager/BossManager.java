package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages boss-fight games.
 *
 * <p>One player (configured in {@code boss.yml} as {@code boss-player}) becomes
 * the Boss when entering a world where a game is active.  The Boss receives:
 * <ul>
 *   <li>3× entity scale</li>
 *   <li>1000 HP (500 hearts) max health</li>
 *   <li>Resistance II (infinite)</li>
 *   <li>Strength IV (infinite)</li>
 *   <li>−25 % movement speed (attribute modifier)</li>
 * </ul>
 * All other players in that world are Hunters.  When the Boss dies the game
 * ends and every player in the world is teleported to the winners world.
 */
public class BossManager {

    /** NamespacedKey for the −25 % speed modifier so we can remove it cleanly. */
    public static final NamespacedKey SPEED_MOD_KEY =
            new NamespacedKey("eternalworlds", "boss_slowness");

    // ── State ────────────────────────────────────────────────────────────────

    public record BossGame(String worldName,
                           String winnersWorld,
                           String bossPlayerName,
                           UUID   bossUuid) {
        BossGame withBossUuid(UUID uuid) {
            return new BossGame(worldName, winnersWorld, bossPlayerName, uuid);
        }
    }

    private final EternalWorldsPlugin   plugin;
    private final File                  bossFile;

    /** worldName (lower-case) → active game. */
    private final Map<String, BossGame> activeGames          = new HashMap<>();
    /** Boss UUID → winners-world name; set when boss dies, cleared after respawn. */
    private final Map<UUID, String>     pendingRespawnWorld  = new HashMap<>();

    private String bossPlayerName = "";

    // ── Construction / load ───────────────────────────────────────────────────

    public BossManager(EternalWorldsPlugin plugin) {
        this.plugin   = plugin;
        this.bossFile = new File(plugin.getDataFolder(), "boss.yml");
        load();
    }

    public void load() {
        if (!bossFile.exists()) {
            YamlConfiguration def = new YamlConfiguration();
            def.set("boss-player", "PlayerName");
            try { def.save(bossFile); } catch (IOException ignored) {}
            bossPlayerName = "";
            return;
        }
        bossPlayerName = YamlConfiguration.loadConfiguration(bossFile)
                .getString("boss-player", "");
    }

    // ── Config getters/setters ────────────────────────────────────────────────

    public String getBossPlayerName() { return bossPlayerName; }

    public void setBossPlayer(String name) {
        bossPlayerName = name;
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("boss-player", name);
        try { cfg.save(bossFile); } catch (IOException e) {
            plugin.getLogger().warning("[Boss] Failed to save boss.yml: " + e.getMessage());
        }
    }

    // ── Game lifecycle ────────────────────────────────────────────────────────

    /**
     * Starts a boss game in the given world.
     * Returns false if no boss player is configured.
     */
    public boolean startGame(String worldName, String winnersWorld) {
        if (bossPlayerName == null || bossPlayerName.isBlank()) return false;
        String key = worldName.toLowerCase();
        activeGames.put(key, new BossGame(key, winnersWorld, bossPlayerName, null));

        // If boss is already in that world, apply effects immediately
        Player boss = Bukkit.getPlayerExact(bossPlayerName);
        if (boss != null && boss.getWorld().getName().equalsIgnoreCase(worldName)) {
            applyBossEffects(boss, worldName);
            notifyHunters(boss);
        }
        return true;
    }

    /**
     * Stops a boss game without teleporting anyone.
     * Removes boss effects from the boss player if online.
     */
    public boolean stopGame(String worldName) {
        BossGame game = activeGames.remove(worldName.toLowerCase());
        if (game == null) return false;
        if (game.bossUuid() != null) {
            Player boss = Bukkit.getPlayer(game.bossUuid());
            if (boss != null) removeBossEffects(boss);
        }
        return true;
    }

    public BossGame  getGame(String worldName)  { return activeGames.get(worldName.toLowerCase()); }
    public boolean   hasGame(String worldName)  { return activeGames.containsKey(worldName.toLowerCase()); }

    /** Returns true if the given player is the current boss in any active game. */
    public boolean isBossInActiveGame(Player player) {
        for (BossGame g : activeGames.values()) {
            if (g.bossUuid() != null && g.bossUuid().equals(player.getUniqueId())) return true;
        }
        return false;
    }

    // ── Boss effects ──────────────────────────────────────────────────────────

    /** Applies all boss attributes and potion effects to the player. */
    public void applyBossEffects(Player boss, String worldName) {
        BossGame game = activeGames.get(worldName.toLowerCase());
        if (game == null) return;
        activeGames.put(worldName.toLowerCase(), game.withBossUuid(boss.getUniqueId()));

        // 3× entity scale
        AttributeInstance scale = boss.getAttribute(Attribute.GENERIC_SCALE);
        if (scale != null) scale.setBaseValue(3.0);

        // 1000 HP max health (500 hearts)
        AttributeInstance maxHp = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHp != null) {
            maxHp.setBaseValue(1000.0);
            boss.setHealth(1000.0);
        }

        // −25 % movement speed
        AttributeInstance speed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(SPEED_MOD_KEY);
            speed.addModifier(new AttributeModifier(
                    SPEED_MOD_KEY, -0.25, AttributeModifier.Operation.ADD_SCALAR));
        }

        // Resistance II (amplifier 1 = level 2), Strength IV (amplifier 3 = level 4)
        boss.addPotionEffect(new PotionEffect(
                PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 1, false, false, true));
        boss.addPotionEffect(new PotionEffect(
                PotionEffectType.STRENGTH,   Integer.MAX_VALUE, 3, false, false, true));

        boss.sendMessage(Component.text("Вы стали БОССОМ! Выживите, пока охотники пытаются вас убить!")
                .color(NamedTextColor.RED).decorate(TextDecoration.BOLD));
    }

    /** Removes all boss-related attributes and effects. */
    public void removeBossEffects(Player boss) {
        AttributeInstance scale = boss.getAttribute(Attribute.GENERIC_SCALE);
        if (scale != null) scale.setBaseValue(1.0);

        AttributeInstance maxHp = boss.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHp != null) {
            maxHp.setBaseValue(20.0);
            if (boss.getHealth() > 20.0) boss.setHealth(20.0);
        }

        AttributeInstance speed = boss.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SPEED_MOD_KEY);

        boss.removePotionEffect(PotionEffectType.RESISTANCE);
        boss.removePotionEffect(PotionEffectType.STRENGTH);
    }

    // ── Death handling ────────────────────────────────────────────────────────

    /**
     * Called by BossListener when the boss player dies.
     * Ends the game, teleports hunters immediately, queues boss respawn-teleport.
     */
    public void onBossDeath(Player boss) {
        BossGame game = findGameForBoss(boss.getUniqueId());
        if (game == null) return;

        activeGames.remove(game.worldName());
        // Queue boss respawn teleport
        pendingRespawnWorld.put(boss.getUniqueId(), game.winnersWorld());

        final String winnersWorldName = game.winnersWorld();
        final String bossWorldName    = game.worldName();

        // Teleport all other players to winners world 1 tick later
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            org.bukkit.World bossWorld    = Bukkit.getWorld(bossWorldName);
            org.bukkit.World winnersWorld = Bukkit.getWorld(winnersWorldName);
            if (winnersWorld == null) return;

            Component msg = Component.text("Охотники победили! Босс " + boss.getName() + " повержен!")
                    .color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD);

            if (bossWorld != null) {
                for (Player p : bossWorld.getPlayers()) {
                    p.teleport(winnersWorld.getSpawnLocation());
                    p.sendMessage(msg);
                }
            }
        }, 1L);

        Bukkit.broadcast(Component.text("БОСС " + boss.getName() + " повержен! Охотники победили!")
                .color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
    }

    /** Returns the winners-world queued for boss respawn-teleport, or null. */
    public String pollRespawnWorld(UUID bossUuid) {
        return pendingRespawnWorld.remove(bossUuid);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BossGame findGameForBoss(UUID uuid) {
        for (BossGame g : activeGames.values()) {
            if (g.bossUuid() != null && g.bossUuid().equals(uuid)) return g;
        }
        return null;
    }

    /** Sends a "boss has entered" message to all hunters already in the world. */
    public void notifyHunters(Player boss) {
        Component msg = Component.text("БОСС вошел в мир! Ваша цель — убить его!")
                .color(NamedTextColor.RED).decorate(TextDecoration.BOLD);
        for (Player p : boss.getWorld().getPlayers()) {
            if (!p.equals(boss)) p.sendMessage(msg);
        }
    }
}
