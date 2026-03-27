package com.eternalworlds.portals.manager;

import com.eternalworlds.portals.EternalWorldsPlugin;
import com.eternalworlds.portals.model.NpcData;
import com.eternalworlds.portals.util.ColorUtil;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.datafixers.util.Pair;
import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Pose;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EquipmentSlot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Manages player-model NPCs using NMS ServerPlayer entities.
 *
 * <p>Each NPC is a {@code ServerPlayer} backed by a fake Netty {@code EmbeddedChannel}.
 * All outgoing packets are silently discarded; keepalive / tick logic is suppressed
 * in {@link FakePacketListener}.  The entity is added to the world through the official
 * {@code PlayerList.placeNewPlayer} path, so entity tracking (spawn packets, equipment,
 * rotation) works automatically.
 *
 * <p>The entity is added to the world via a direct {@code ServerLevel.addNewPlayer} call
 * (bypassing {@code PlayerList.placeNewPlayer}) to avoid the protocol-direction validation
 * that would reject a fake connection.  Skin packets are broadcast manually.
 *
 * <p>Skin data is stored in {@code skins.yml} as Base64 texture value + Mojang signature.
 */
public class NpcManager {

    // ── Skin record ───────────────────────────────────────────────────────────

    public record SkinEntry(String value, String signature) {}

    // ── Inner fake packet listener ────────────────────────────────────────────

    /**
     * Replaces the real {@code ServerGamePacketListenerImpl} for an NPC.
     * All send operations and ticking are suppressed so the entity never
     * disconnects due to keepalive timeouts.
     */
    private static final class FakePacketListener extends ServerGamePacketListenerImpl {

        FakePacketListener(MinecraftServer server, Connection conn,
                           ServerPlayer player, CommonListenerCookie cookie) {
            super(server, conn, player, cookie);
        }

        /** Suppress all ticking: keepalive, position-sync, etc. */
        @Override
        public void tick() {}

        /** Suppress all outgoing packets. */
        @Override
        public void send(Packet<?> packet) {}

        /** Suppress all outgoing packets. */
        @Override
        public void send(Packet<?> packet, @Nullable net.minecraft.network.PacketSendListener listener) {
            if (listener != null) listener.onSuccess();
        }

        /** Never actually disconnects the fake entity. */
        @Override
        public void onDisconnect(net.minecraft.network.DisconnectionDetails details) {}
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private final EternalWorldsPlugin plugin;
    private final File npcsFile;
    private final File skinsFile;

    private final Map<String, NpcData>     npcs        = new LinkedHashMap<>();
    private final Map<String, SkinEntry>   skins       = new LinkedHashMap<>();
    private final Map<String, ServerPlayer> activeNpcs   = new HashMap<>();
    private final Map<String, TextDisplay>  nameDisplays = new HashMap<>();
    /** Invisible armor-stand seat used for the "sitting" pose. */
    private final Map<String, org.bukkit.entity.Entity> seatEntities = new HashMap<>();
    /** Maps NMS entity ID → NPC id for fast look-up in event handlers. */
    private final Map<Integer, String>      entityIdMap  = new HashMap<>();
    /** Tracks NPC UUIDs so we can recognize join/quit events as fake. */
    private final Set<UUID>                 npcUuids     = new HashSet<>();

    private static final String HIDE_TEAM = "ewp_npc_names";

    public NpcManager(EternalWorldsPlugin plugin) {
        this.plugin   = plugin;
        this.npcsFile = new File(plugin.getDataFolder(), "npcs.yml");
        this.skinsFile = new File(plugin.getDataFolder(), "skins.yml");
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    public void load() {
        loadSkins();
        loadNpcs();
    }

    private void loadSkins() {
        if (!skinsFile.exists()) {
            skinsFile.getParentFile().mkdirs();
            try {
                java.nio.file.Files.writeString(skinsFile.toPath(),
                        "# skins.yml - NPC skin library\n" +
                        "# Add skins in-game:  /npc addskin <name> <base64value> <signature>\n" +
                        "# Get value+signature from https://mineskin.org\n" +
                        "#\n" +
                        "# Manual format:\n" +
                        "# skins:\n" +
                        "#   myskin:\n" +
                        "#     value: <base64 texture value>\n" +
                        "#     signature: <mojang signature>\n" +
                        "skins: {}\n");
            } catch (IOException e) {
                plugin.getLogger().warning("[NPC] Failed to create skins.yml: " + e.getMessage());
            }
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(skinsFile);
        ConfigurationSection sec = cfg.getConfigurationSection("skins");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            String value = sec.getString("skins." + name + ".value");
            String sig   = sec.getString("skins." + name + ".signature");
            if (value != null && sig != null) skins.put(name, new SkinEntry(value, sig));
        }
    }

    /**
     * Adds (or replaces) a skin entry and immediately persists {@code skins.yml}.
     * Called from {@code /npc addskin}.
     */
    public void addSkin(String name, String value, String signature) {
        skins.put(name.toLowerCase(), new SkinEntry(value, signature));
        saveSkins();
    }

    public void saveSkins() {
        YamlConfiguration cfg = new YamlConfiguration();
        skins.forEach((name, e) -> {
            cfg.set("skins." + name + ".value",     e.value());
            cfg.set("skins." + name + ".signature", e.signature());
        });
        trySave(cfg, skinsFile);
    }

    private void loadNpcs() {
        if (!npcsFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(npcsFile);
        ConfigurationSection sec = cfg.getConfigurationSection("npcs");
        if (sec == null) return;

        for (String id : sec.getKeys(false)) {
            String path = "npcs." + id;
            UUID uuid;
            try {
                uuid = UUID.fromString(cfg.getString(path + ".uuid", ""));
            } catch (IllegalArgumentException e) {
                uuid = UUID.nameUUIDFromBytes(("EWP_NPC:" + id).getBytes());
            }
            String  displayName   = cfg.getString(path + ".display-name");
            String  worldName     = cfg.getString(path + ".world", "world");
            double  x             = cfg.getDouble(path + ".x");
            double  y             = cfg.getDouble(path + ".y");
            double  z             = cfg.getDouble(path + ".z");
            float   yaw           = (float) cfg.getDouble(path + ".yaw");
            String  skinName      = cfg.getString(path + ".skin");
            String  clickCommand  = cfg.getString(path + ".click-command");
            boolean lookAtNearest = cfg.getBoolean(path + ".look-at-nearest", true);

            Map<String, org.bukkit.inventory.ItemStack> equipment = new LinkedHashMap<>();
            String eqPath = path + ".equipment";
            if (cfg.isConfigurationSection(eqPath)) {
                for (String slot : cfg.getConfigurationSection(eqPath).getKeys(false)) {
                    org.bukkit.inventory.ItemStack item = cfg.getItemStack(eqPath + "." + slot);
                    if (item != null) equipment.put(slot, item);
                }
            }

            String pose = cfg.getString(path + ".pose", "standing");
            npcs.put(id, new NpcData(id, uuid, displayName, worldName,
                    x, y, z, yaw, skinName, clickCommand, lookAtNearest, pose, equipment));
        }

        // Spawn after a 1-tick delay so all worlds are loaded
        plugin.getServer().getScheduler().runTask(plugin, this::spawnAllEntities);
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        npcs.forEach((id, data) -> {
            String path = "npcs." + id;
            cfg.set(path + ".uuid",             data.getUuid().toString());
            if (data.getDisplayName() != null)
                cfg.set(path + ".display-name", data.getDisplayName());
            cfg.set(path + ".world",            data.getWorldName());
            cfg.set(path + ".x",                data.getX());
            cfg.set(path + ".y",                data.getY());
            cfg.set(path + ".z",                data.getZ());
            cfg.set(path + ".yaw",              (double) data.getYaw());
            if (data.getSkinName() != null)
                cfg.set(path + ".skin",         data.getSkinName());
            if (data.getClickCommand() != null)
                cfg.set(path + ".click-command",data.getClickCommand());
            cfg.set(path + ".look-at-nearest",  data.isLookAtNearest());
            cfg.set(path + ".pose",             data.getPose());
            data.getEquipment().forEach((slot, item) ->
                    cfg.set(path + ".equipment." + slot, item));
        });
        trySave(cfg, npcsFile);
    }

    private void trySave(YamlConfiguration cfg, File file) {
        try { cfg.save(file); } catch (IOException e) {
            plugin.getLogger().warning("[NPC] Failed to save " + file.getName() + ": " + e.getMessage());
        }
    }

    // ── NPC lifecycle ──────────────────────────────────────────────────────────

    /** Creates a new NPC at the given location and spawns its entity. */
    public boolean createNpc(String id, Location location) {
        id = id.toLowerCase();
        if (npcs.containsKey(id)) return false;
        NpcData data = new NpcData(id,
                location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(),
                location.getYaw());
        npcs.put(id, data);
        npcUuids.add(data.getUuid());
        spawnEntity(data);
        save();
        return true;
    }

    /** Removes an NPC completely (despawns entity + deletes config). */
    public boolean removeNpc(String id) {
        id = id.toLowerCase();
        NpcData data = npcs.remove(id);
        if (data == null) return false;
        npcUuids.remove(data.getUuid());
        despawnEntity(id);
        save();
        return true;
    }

    public @Nullable NpcData          getNpc(String id)   { return npcs.get(id.toLowerCase()); }
    public Collection<NpcData>        getAllNpcs()         { return npcs.values(); }
    public Map<String, SkinEntry>     getSkins()          { return skins; }
    public boolean                    isNpcUuid(UUID uuid) { return npcUuids.contains(uuid); }

    /**
     * Despawns all active NPC entities, clears in-memory state, then reloads
     * both {@code npcs.yml} and {@code skins.yml} and re-spawns every NPC.
     */
    public void reload() {
        // 1. Despawn every live entity
        despawnAll();

        // 2. Clear in-memory state (despawnAll already cleared activeNpcs/nameDisplays/entityIdMap)
        npcs.clear();
        skins.clear();
        npcUuids.clear();

        // 3. Reload from disk and re-spawn
        load();
    }

    // ── Spawning ──────────────────────────────────────────────────────────────

    private void spawnAllEntities() {
        for (NpcData data : npcs.values()) {
            npcUuids.add(data.getUuid());
            spawnEntity(data);
        }
    }

    public void spawnEntity(NpcData data) {
        World world = plugin.getServer().getWorld(data.getWorldName());
        if (world == null) return;
        if (activeNpcs.containsKey(data.getId())) despawnEntity(data.getId());

        MinecraftServer nmsServer = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel     level     = ((CraftWorld) world).getHandle();

        // Build GameProfile with optional skin
        GameProfile profile = new GameProfile(data.getUuid(),
                "npc_" + data.getId().substring(0, Math.min(data.getId().length(), 11)));
        SkinEntry skin = data.getSkinName() != null ? skins.get(data.getSkinName()) : null;
        if (skin != null) {
            profile.getProperties().put("textures",
                    new Property("textures", skin.value(), skin.signature()));
        }

        // Create fake Connection backed by an EmbeddedChannel (absorbs all packets)
        Connection fakeConn = buildFakeConnection();

        // Create ServerPlayer
        ClientInformation       info   = ClientInformation.createDefault();
        CommonListenerCookie    cookie = new CommonListenerCookie(profile, 0, info, false);
        ServerPlayer            npc    = new ServerPlayer(nmsServer, level, profile, info);

        npc.setPos(data.getX(), data.getY(), data.getZ());
        npc.setYRot(data.getYaw());
        npc.setYHeadRot(data.getYaw());
        npc.setXRot(0f);
        npc.setInvulnerable(true);
        npc.setSilent(true);
        npc.noPhysics = true;

        // Attach fake listener (constructor also sets npc.connection = this)
        new FakePacketListener(nmsServer, fakeConn, npc, cookie);

        // Bypass placeNewPlayer entirely: it calls setupInboundProtocol which validates
        // the connection direction AND replaces our FakePacketListener with a real one.
        // Add the entity directly to the world level instead.
        try {
            java.lang.reflect.Method addNewPlayer =
                    net.minecraft.server.level.ServerLevel.class
                            .getDeclaredMethod("addNewPlayer", ServerPlayer.class);
            addNewPlayer.setAccessible(true);
            addNewPlayer.invoke(level, npc);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw new RuntimeException("Failed to add NPC to world", ite.getCause() != null ? ite.getCause() : ite);
        } catch (Exception e) {
            throw new RuntimeException("Failed to add NPC to world", e);
        }

        // Entity tracking for fake ServerPlayer entities does not reliably send spawn packets
        // to online clients. Broadcast all required packets manually after a short delay so
        // the entity is fully initialized before we send.
        final String npcId = data.getId();
        final UUID   npcUuid = npc.getUUID();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            ServerPlayer live = activeNpcs.get(npcId);
            if (live == null) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!npcUuids.contains(p.getUniqueId())) {
                    broadcastNpcSpawnToPlayer(live, p);
                }
            }
            // Remove NPC from tab list once clients have cached the skin
            plugin.getServer().getScheduler().runTaskLater(plugin, () ->
                    removeNpcFromTabList(npcUuid), 40L);
        }, 3L);

        activeNpcs.put(data.getId(), npc);
        entityIdMap.put(npc.getId(), data.getId());

        // Apply equipment
        data.getEquipment().forEach((slot, item) ->
                applyEquipmentToEntity(npc, world, slot, item));

        // Hide the default (white) profile-name tag via a scoreboard team
        hideDefaultNameTag(profile.getName());

        // Spawn TextDisplay for custom name (if set)
        if (data.getDisplayName() != null) spawnNameDisplay(data, world);

        // Apply persisted pose (crouching / sitting)
        applyPose(data, npc, world);
    }

    public void despawnEntity(String id) {
        id = id.toLowerCase();
        // Remove seat entity (used for sitting pose)
        org.bukkit.entity.Entity seat = seatEntities.remove(id);
        if (seat != null && !seat.isDead()) seat.remove();

        TextDisplay display = nameDisplays.remove(id);
        if (display != null && !display.isDead()) display.remove();

        ServerPlayer npc = activeNpcs.remove(id);
        if (npc == null) return;
        entityIdMap.remove(npc.getId());

        // Remove team entry so the fake name disappears
        NpcData data = npcs.get(id);
        if (data != null) {
            String profileName = "npc_" + data.getId().substring(0, Math.min(data.getId().length(), 11));
            removeHideTeamEntry(profileName);
        }

        // Remove the entity from the world cleanly.
        // We never went through placeNewPlayer so we must not call playerList.remove().
        // Discard the entity directly; ServerLevel will handle entity tracking cleanup.
        npc.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
    }

    /** Despawns all NPC entities (called on plugin disable). */
    public void despawnAll() {
        new ArrayList<>(activeNpcs.keySet()).forEach(this::despawnEntity);
    }

    // ── Fake Connection ───────────────────────────────────────────────────────

    private static Connection buildFakeConnection() {
        try {
            // Find the Connection(PacketFlow) constructor and invoke it with SERVERBOUND.
            // We use name() (canonical enum identifier) rather than toString() which can be
            // overridden. We deliberately skip no-arg constructors so the direction is always
            // explicitly SERVERBOUND — required by Connection.validateListener().
            Connection conn = null;
            for (java.lang.reflect.Constructor<?> ctor : Connection.class.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 1 && params[0].isEnum()) {
                    Object serverbound = null;
                    for (Object ec : params[0].getEnumConstants()) {
                        if (((Enum<?>) ec).name().equals("SERVERBOUND")) {
                            serverbound = ec;
                            break;
                        }
                    }
                    if (serverbound != null) {
                        conn = (Connection) ctor.newInstance(serverbound);
                        break;
                    }
                }
            }
            if (conn == null) throw new IllegalStateException("Cannot find Connection(PacketFlow) constructor");

            // Inject an EmbeddedChannel so Connection thinks it's connected
            Field chField = Connection.class.getDeclaredField("channel");
            chField.setAccessible(true);
            chField.set(conn, new EmbeddedChannel());

            // Provide a non-null remote address to avoid NPE in logging
            Field addrField = Connection.class.getDeclaredField("address");
            addrField.setAccessible(true);
            addrField.set(conn, new java.net.InetSocketAddress("127.0.0.1", 0));
            return conn;
        } catch (Exception e) {
            throw new RuntimeException("Failed to build fake NPC connection", e);
        }
    }

    // ── Player join/quit handling ─────────────────────────────────────────────

    /**
     * Called from NpcListener when any player joins.
     * Sends PlayerInfo (skin) packets for all active NPCs to the new player.
     */
    public void onRealPlayerJoin(Player player) {
        if (activeNpcs.isEmpty()) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // Send PlayerInfo + full spawn packets for every active NPC so the joining
            // player can see them all.
            for (ServerPlayer npc : new ArrayList<>(activeNpcs.values())) {
                broadcastNpcSpawnToPlayer(npc, player);
            }
            // Remove NPCs from tab list shortly after (skins already cached)
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                List<UUID> uuids = activeNpcs.values().stream().map(ServerPlayer::getUUID).toList();
                ((CraftPlayer) player).getHandle().connection
                        .send(new ClientboundPlayerInfoRemovePacket(uuids));
            }, 40L);
        }, 10L);
    }

    /**
     * Called from NpcListener for PlayerJoinEvent/PlayerQuitEvent of a fake NPC.
     * Returns true if the player is one of our NPCs.
     */
    public boolean isNpcPlayer(Player player) {
        return npcUuids.contains(player.getUniqueId());
    }

    /** Removes NPC's tab-list entry for all players right after spawn. */
    public void removeNpcFromTabList(UUID npcUuid) {
        var packet = new ClientboundPlayerInfoRemovePacket(List.of(npcUuid));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!npcUuids.contains(p.getUniqueId())) {
                ((CraftPlayer) p).getHandle().connection.send(packet);
            }
        }
    }

    /**
     * Sends all packets required for one online player to see the given NPC:
     * PlayerInfo (skin), AddEntity, EntityData, head rotation, and equipment.
     * PlayerInfo must arrive at the client before AddEntity or the skin won't render.
     */
    private void broadcastNpcSpawnToPlayer(ServerPlayer npc, Player target) {
        var conn = ((CraftPlayer) target).getHandle().connection;
        // 1. Profile / skin — must arrive before the spawn packet
        conn.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(npc)));
        // 2. Spawn entity
        conn.send(new ClientboundAddEntityPacket(
                npc.getId(), npc.getUUID(),
                npc.getX(), npc.getY(), npc.getZ(),
                npc.getXRot(), npc.getYRot(),
                npc.getType(), 0,
                Vec3.ZERO,
                (double) npc.getYHeadRot()));
        // 3. Entity data (skin layer bits, pose flags, etc.)
        List<SynchedEntityData.DataValue<?>> dataValues = npc.getEntityData().packAll();
        if (dataValues != null && !dataValues.isEmpty()) {
            conn.send(new ClientboundSetEntityDataPacket(npc.getId(), dataValues));
        }
        // 4. Head yaw (so NPC faces the correct direction)
        conn.send(new ClientboundRotateHeadPacket(npc,
                (byte) (npc.getYHeadRot() * 256.0F / 360.0F)));
        // 5. Equipment
        List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> equip = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            net.minecraft.world.item.ItemStack item = npc.getItemBySlot(slot);
            if (!item.isEmpty()) equip.add(Pair.of(slot, item));
        }
        if (!equip.isEmpty()) {
            conn.send(new ClientboundSetEquipmentPacket(npc.getId(), equip));
        }
    }

    /**
     * Moves NPC {@code id} to {@code loc}: updates persisted data, despawns
     * the old entity, and spawns a new one at the new position.
     *
     * @return false if no NPC with that id exists
     */
    public boolean moveNpcHere(String id, Location loc) {
        NpcData data = npcs.get(id.toLowerCase());
        if (data == null) return false;
        data.setWorldName(loc.getWorld().getName());
        data.setX(loc.getX());
        data.setY(loc.getY());
        data.setZ(loc.getZ());
        data.setYaw(loc.getYaw());
        save();
        despawnEntity(id);
        spawnEntity(data);
        return true;
    }

    // ── Name display ──────────────────────────────────────────────────────────

    private void spawnNameDisplay(NpcData data, World world) {
        TextDisplay old = nameDisplays.remove(data.getId());
        if (old != null && !old.isDead()) old.remove();

        Location loc = new Location(world, data.getX(), data.getY() + 2.15, data.getZ());
        TextDisplay display = (TextDisplay) world.spawnEntity(loc, EntityType.TEXT_DISPLAY);
        Component text = LegacyComponentSerializer.legacySection()
                .deserialize(ColorUtil.parse(data.getDisplayName()));
        display.text(text);
        display.setBillboard(Display.Billboard.CENTER);
        display.setDefaultBackground(false);
        display.setShadowed(true);
        display.setPersistent(false);
        nameDisplays.put(data.getId(), display);
    }

    public void updateDisplayName(String id, @Nullable String displayName) {
        NpcData data = npcs.get(id.toLowerCase());
        if (data == null) return;
        data.setDisplayName(displayName);
        save();
        TextDisplay old = nameDisplays.remove(id);
        if (old != null && !old.isDead()) old.remove();
        if (displayName != null) {
            World world = plugin.getServer().getWorld(data.getWorldName());
            if (world != null) spawnNameDisplay(data, world);
        }
    }

    // ── Pose ──────────────────────────────────────────────────────────────────

    /** Valid pose names accepted by commands. */
    public static final List<String> VALID_POSES = List.of("standing", "crouching", "sitting");

    /**
     * Updates the NPC's pose, persists it to {@code npcs.yml}, and applies it
     * to the live entity immediately.
     */
    public boolean setPose(String id, String pose) {
        NpcData data = npcs.get(id.toLowerCase());
        if (data == null) return false;
        data.setPose(pose);
        save();
        ServerPlayer npc = activeNpcs.get(id.toLowerCase());
        if (npc != null) {
            World world = plugin.getServer().getWorld(data.getWorldName());
            if (world != null) {
                // Remove any existing seat before applying new pose
                org.bukkit.entity.Entity oldSeat = seatEntities.remove(id.toLowerCase());
                if (oldSeat != null && !oldSeat.isDead()) oldSeat.remove();
                applyPose(data, npc, world);
            }
        }
        return true;
    }

    /**
     * Applies the pose stored in {@code data} to the live NMS entity.
     * Called both on initial spawn and when pose is changed at runtime.
     */
    private void applyPose(NpcData data, ServerPlayer npc, World world) {
        switch (data.getPose()) {
            case "crouching" -> {
                npc.setPose(Pose.CROUCHING);
                sendEntityMetadata(npc, world);
            }
            case "sitting" -> {
                // Reset to standing first so the entity can be mounted
                npc.setPose(Pose.STANDING);
                sendEntityMetadata(npc, world);
                spawnSeat(data, npc, world);
            }
            default -> { // "standing"
                npc.setPose(Pose.STANDING);
                sendEntityMetadata(npc, world);
            }
        }
    }

    /**
     * Spawns an invisible small armor stand at the NPC's position and makes
     * the NPC ride it, producing a natural "sitting" appearance.
     *
     * <p>The armor stand is offset -0.6 on Y so the NPC appears to sit at
     * approximately the same ground level as when standing.
     */
    private void spawnSeat(NpcData data, ServerPlayer npc, World world) {
        // Small armor stand passenger-riding offset ≈ 0.60 blocks
        Location seatLoc = new Location(world, data.getX(), data.getY() - 0.6, data.getZ());
        org.bukkit.entity.ArmorStand seat =
                (org.bukkit.entity.ArmorStand) world.spawnEntity(seatLoc, EntityType.ARMOR_STAND);
        seat.setInvisible(true);
        seat.setSmall(true);
        seat.setArms(false);
        seat.setBasePlate(false);
        seat.setGravity(false);
        seat.setSilent(true);
        seat.setInvulnerable(true);
        seat.setPersistent(false);
        seat.addPassenger(npc.getBukkitEntity());
        seatEntities.put(data.getId(), seat);
    }

    /**
     * Sends a full entity-data (metadata) packet to every real player in
     * the NPC's world so pose changes are visible immediately.
     */
    private void sendEntityMetadata(ServerPlayer npc, World world) {
        var dataValues = npc.getEntityData().packAll();
        if (dataValues == null || dataValues.isEmpty()) return;
        var packet = new ClientboundSetEntityDataPacket(npc.getId(), dataValues);
        for (Player p : world.getPlayers()) {
            if (!npcUuids.contains(p.getUniqueId()))
                ((CraftPlayer) p).getHandle().connection.send(packet);
        }
    }

    // ── Look-at-nearest scheduler ─────────────────────────────────────────────

    public void startScheduler() {
        // Every 4 ticks (~200ms): rotate NPCs to face nearest player
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickLookAt, 4L, 4L);
    }

    private void tickLookAt() {
        for (Map.Entry<String, ServerPlayer> e : activeNpcs.entrySet()) {
            NpcData data = npcs.get(e.getKey());
            if (data == null || !data.isLookAtNearest()) continue;
            ServerPlayer npc = e.getValue();
            if (npc.isRemoved()) continue;
            World world = plugin.getServer().getWorld(data.getWorldName());
            if (world == null) continue;

            Player nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (Player p : world.getPlayers()) {
                if (npcUuids.contains(p.getUniqueId())) continue;
                double d = squareDist(p.getLocation(), npc.getX(), npc.getY(), npc.getZ());
                if (d < nearestDist) { nearestDist = d; nearest = p; }
            }
            if (nearest == null) continue;
            rotateTo(npc, nearest, world);
        }
    }

    private static double squareDist(Location loc, double x, double y, double z) {
        double dx = loc.getX() - x, dy = loc.getY() - y, dz = loc.getZ() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void rotateTo(ServerPlayer npc, Player target, World world) {
        double dx   = target.getLocation().getX()       - npc.getX();
        double dy   = target.getEyeLocation().getY()    - npc.getEyeY();
        double dz   = target.getLocation().getZ()       - npc.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        float yaw   = (float)  Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        npc.setYRot(yaw);
        npc.setYHeadRot(yaw);
        npc.setXRot(pitch);

        byte yawB   = (byte) (yaw   * 256f / 360f);
        byte pitchB = (byte) (pitch * 256f / 360f);

        var rotPacket  = new ClientboundMoveEntityPacket.Rot(npc.getId(), yawB, pitchB, npc.onGround());
        var headPacket = new ClientboundRotateHeadPacket(npc, yawB);

        for (Player p : world.getPlayers()) {
            if (npcUuids.contains(p.getUniqueId())) continue;
            ((CraftPlayer) p).getHandle().connection.send(rotPacket);
            ((CraftPlayer) p).getHandle().connection.send(headPacket);
        }
    }

    // ── Equipment ─────────────────────────────────────────────────────────────

    public void applyEquipment(String id, String slot, @Nullable org.bukkit.inventory.ItemStack item) {
        NpcData data = npcs.get(id.toLowerCase());
        if (data == null) return;
        data.setEquipmentSlot(slot, item);
        save();
        ServerPlayer npc = activeNpcs.get(id.toLowerCase());
        if (npc == null) return;
        World world = plugin.getServer().getWorld(data.getWorldName());
        if (world != null) applyEquipmentToEntity(npc, world, slot, item);
    }

    private void applyEquipmentToEntity(ServerPlayer npc, World world,
                                         String slotName,
                                         @Nullable org.bukkit.inventory.ItemStack item) {
        EquipmentSlot nmsSlot = toNmsSlot(slotName);
        if (nmsSlot == null) return;
        net.minecraft.world.item.ItemStack nmsItem = CraftItemStack.asNMSCopy(item);
        npc.setItemSlot(nmsSlot, nmsItem);

        var packet = new ClientboundSetEquipmentPacket(npc.getId(),
                List.of(new Pair<>(nmsSlot, nmsItem)));
        for (Player p : world.getPlayers()) {
            if (!npcUuids.contains(p.getUniqueId()))
                ((CraftPlayer) p).getHandle().connection.send(packet);
        }
    }

    private static @Nullable EquipmentSlot toNmsSlot(String slot) {
        return switch (slot.toLowerCase()) {
            case "mainhand"   -> EquipmentSlot.MAINHAND;
            case "offhand"    -> EquipmentSlot.OFFHAND;
            case "helmet"     -> EquipmentSlot.HEAD;
            case "chestplate" -> EquipmentSlot.CHEST;
            case "leggings"   -> EquipmentSlot.LEGS;
            case "boots"      -> EquipmentSlot.FEET;
            default           -> null;
        };
    }

    // ── Skin change ───────────────────────────────────────────────────────────

    /** Re-spawns the NPC to apply a new skin. */
    public void applySkin(String id, @Nullable String skinName) {
        NpcData data = npcs.get(id.toLowerCase());
        if (data == null) return;
        data.setSkinName(skinName);
        save();
        despawnEntity(id);
        spawnEntity(data);
    }

    // ── Scoreboard team (hide default profile-name tag) ───────────────────────

    private void hideDefaultNameTag(String profileName) {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = board.getTeam(HIDE_TEAM);
        if (team == null) {
            team = board.registerNewTeam(HIDE_TEAM);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            team.setOption(Team.Option.COLLISION_RULE,      Team.OptionStatus.NEVER);
        }
        team.addEntry(profileName);
    }

    private void removeHideTeamEntry(String profileName) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(HIDE_TEAM);
        if (team != null) team.removeEntry(profileName);
    }

    // ── Interaction detection ─────────────────────────────────────────────────

    /** Returns the NPC id for a given NMS entity ID, or {@code null} if not an NPC. */
    public @Nullable String getNpcIdByEntityId(int entityId) {
        return entityIdMap.get(entityId);
    }
}
