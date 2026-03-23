package com.eternalworlds.portals.model;

/**
 * Represents a custom portal: an axis-aligned cuboid in a source world
 * that teleports players to a destination world.
 */
public class Portal {

    private final String name;
    private final String sourceWorld;

    // Bounding box (always stored as min/max so lookups are O(1))
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    private final String destinationWorld;

    // Destination coordinates (world spawn is used when a portal is first created)
    private double destX, destY, destZ;
    private float destYaw, destPitch;

    private boolean enabled;

    public Portal(String name, String sourceWorld,
                  int x1, int y1, int z1,
                  int x2, int y2, int z2,
                  String destinationWorld,
                  double destX, double destY, double destZ,
                  float destYaw, float destPitch,
                  boolean enabled) {
        this.name = name;
        this.sourceWorld = sourceWorld;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
        this.destinationWorld = destinationWorld;
        this.destX = destX;
        this.destY = destY;
        this.destZ = destZ;
        this.destYaw = destYaw;
        this.destPitch = destPitch;
        this.enabled = enabled;
    }

    /** Returns true if the given block position is inside this portal's bounding box. */
    public boolean contains(String world, int x, int y, int z) {
        return sourceWorld.equals(world)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    // ---- Mutable state ----

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setDestination(double x, double y, double z, float yaw, float pitch) {
        this.destX = x;
        this.destY = y;
        this.destZ = z;
        this.destYaw = yaw;
        this.destPitch = pitch;
    }

    // ---- Getters ----

    public String getName()             { return name; }
    public String getSourceWorld()      { return sourceWorld; }
    public String getDestinationWorld() { return destinationWorld; }
    public int getMinX()                { return minX; }
    public int getMinY()                { return minY; }
    public int getMinZ()                { return minZ; }
    public int getMaxX()                { return maxX; }
    public int getMaxY()                { return maxY; }
    public int getMaxZ()                { return maxZ; }
    public double getDestX()            { return destX; }
    public double getDestY()            { return destY; }
    public double getDestZ()            { return destZ; }
    public float getDestYaw()           { return destYaw; }
    public float getDestPitch()         { return destPitch; }
    public boolean isEnabled()          { return enabled; }
}
