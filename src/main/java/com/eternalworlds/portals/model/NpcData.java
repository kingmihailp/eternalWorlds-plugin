package com.eternalworlds.portals.model;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class NpcData {

    private final String id;
    private final UUID   uuid;
    private String  displayName;    // nullable — hex-supported
    private String  worldName;
    private double  x, y, z;
    private float   yaw;
    private String  skinName;       // nullable — key in skins.yml
    private String  clickCommand;   // nullable — supports {player}, {npc}
    private boolean lookAtNearest;  // default: true
    private final Map<String, ItemStack> equipment = new LinkedHashMap<>();

    /** Create a brand-new NPC (UUID derived from id for stability). */
    public NpcData(String id, String worldName, double x, double y, double z, float yaw) {
        this.id           = id;
        this.uuid         = UUID.nameUUIDFromBytes(("EWP_NPC:" + id).getBytes());
        this.worldName    = worldName;
        this.x = x; this.y = y; this.z = z;
        this.yaw          = yaw;
        this.lookAtNearest = true;
    }

    /** Full constructor used when loading from npcs.yml. */
    public NpcData(String id, UUID uuid, String displayName,
                   String worldName, double x, double y, double z, float yaw,
                   String skinName, String clickCommand, boolean lookAtNearest,
                   Map<String, ItemStack> equipment) {
        this.id           = id;
        this.uuid         = uuid;
        this.displayName  = displayName;
        this.worldName    = worldName;
        this.x = x; this.y = y; this.z = z;
        this.yaw          = yaw;
        this.skinName     = skinName;
        this.clickCommand = clickCommand;
        this.lookAtNearest = lookAtNearest;
        this.equipment.putAll(equipment);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String  getId()             { return id; }
    public UUID    getUuid()           { return uuid; }
    public String  getDisplayName()    { return displayName; }
    public String  getWorldName()      { return worldName; }
    public double  getX()              { return x; }
    public double  getY()              { return y; }
    public double  getZ()              { return z; }
    public float   getYaw()            { return yaw; }
    public String  getSkinName()       { return skinName; }
    public String  getClickCommand()   { return clickCommand; }
    public boolean isLookAtNearest()   { return lookAtNearest; }
    public Map<String, ItemStack> getEquipment() { return equipment; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setDisplayName(String v)    { this.displayName  = v; }
    public void setSkinName(String v)       { this.skinName     = v; }
    public void setClickCommand(String v)   { this.clickCommand = v; }
    public void setLookAtNearest(boolean v) { this.lookAtNearest = v; }

    public void setEquipmentSlot(String slot, ItemStack item) {
        if (item == null || item.getType().isAir()) equipment.remove(slot);
        else equipment.put(slot, item.clone());
    }
}
