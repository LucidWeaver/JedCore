package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EarthSurf extends EarthAbility implements AddonAbility {
    private static final double VERTICAL_RESPONSE_TICKS = 3.0;
    private static final double MAX_VERTICAL_SPEED = 0.5;
    private static final double GROUND_REACH_UP = 1.0;
    private static final double GROUND_PROBE_WIDTH = 0.4;
    private static final double WAVE_VISUAL_DISTANCE = 2.5;
    private static final double WAVE_TERRAIN_DISTANCE = 2.0;
    private static final double WAVE_LANE_DISTANCE = 1.0;
    private static final double WAVE_VISUAL_EYE_OFFSET = 2.9;
    private static final double WAVE_SURFACE_CLEARANCE = 0.02;
    private static final double WAVE_FALLING_BLOCK_SIZE = 0.98;
    private static final double WAVE_FALLING_BLOCK_MAX_RISE = 0.62;
    private static final double WAVE_PUSH_RADIUS = 1.5;
    private static final double WAVE_PUSH_VELOCITY = 0.3;
    private static final double COLLISION_EPSILON = 1.0E-7;
    private static final long WAVE_REVERT_FALLBACK = 1000L;
    private static final int WAVE_SURFACE_RETURN_TICKS = 12;
    private static final int WAVE_LANE_COUNT = 3;

    private Location location;

    @Attribute(Attribute.COOLDOWN)
    private long maximumCooldown;
    private long minimumCooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    private boolean cooldownEnabled;
    private boolean cooldownScaled;
    private boolean durationEnabled;
    private boolean removeOnDamage;
    @Attribute(Attribute.SPEED)
    private double speed;
    private double rideHeight;
    private double maxGroundDistance;
    private int terrainGraceTicks;
    private int missedGroundTicks;
    private final Set<Block> ridingBlocks = new HashSet<>();
    private final Map<Block, RidingSurface> ridingSurfaces = new HashMap<>();
    private final Map<Block, Long> waveTerrainExpiryTicks = new HashMap<>();
    private long waveTick;

    public EarthSurf(Player player) {
        super(player);

        if (player == null || bPlayer == null || !bPlayer.canBend(this)) {
            return;
        }

        if (hasAbility(player, EarthSurf.class)) {
            getAbility(player, EarthSurf.class).remove();
            return;
        }

        setFields();
        location = player.getLocation();

        if (!canStart()) {
            return;
        }

        this.flightHandler.createInstance(player, this.getName());
        player.setAllowFlight(true);
        player.setFlying(false);
        start();
    }

    private boolean canStart() {
        GroundHit ground = findGround(player.getLocation());
        return ground != null
                && !ground.virtual
                && isEarthbendable(player, ground.block)
                && !isMetal(ground.block)
                && !RegionProtection.isRegionProtected(this, ground.block.getLocation())
                && !RegionProtection.isRegionProtected(this, player.getLocation());
    }

    public void setFields() {
        ConfigurationSection config = JedCoreConfig.getConfig(this.player);
        String root = "Abilities.Earth.EarthSurf";

        maximumCooldown = config.getLong(root + ".Cooldown.Maximum");
        minimumCooldown = config.getLong(root + ".Cooldown.Minimum");
        cooldownEnabled = config.getBoolean(root + ".Cooldown.Enabled");
        cooldownScaled = config.getBoolean(root + ".Cooldown.Scaled");
        duration = config.getLong(root + ".Duration.Milliseconds");
        durationEnabled = config.getBoolean(root + ".Duration.Enabled");
        removeOnDamage = config.getBoolean(root + ".RemoveOnDamage");
        speed = config.getDouble(root + ".Speed");
        rideHeight = config.getDouble(root + ".RideHeight");
        maxGroundDistance = config.getDouble(root + ".MaxGroundDistance");
        terrainGraceTicks = config.getInt(root + ".TerrainGraceTicks");
    }

    @Override
    public void progress() {
        waveTick++;
        revertExpiredWaveTerrain();

        if (shouldRemove()) {
            remove();
            return;
        }

        player.setFlying(false);
        GroundHit ground = findGround(player.getLocation());

        if (ground == null) {
            missedGroundTicks++;
            if (missedGroundTicks > terrainGraceTicks) {
                remove();
                return;
            }
        } else {
            if (!ground.virtual && (!isEarthbendable(player, ground.block)
                    || RegionProtection.isRegionProtected(this, ground.block.getLocation()))) {
                remove();
                return;
            }
            missedGroundTicks = 0;
        }

        movePlayer(ground);
    }

    private boolean shouldRemove() {
        if (player == null || player.isDead() || !player.isOnline()) {
            return true;
        }
        if (bPlayer == null || !bPlayer.canBendIgnoreCooldowns(this)) {
            return true;
        }
        if (RegionProtection.isRegionProtected(this, player.getLocation())) {
            return true;
        }
        if (durationEnabled && System.currentTimeMillis() >= getStartTime() + duration) {
            return true;
        }
        return player.isSneaking();
    }

    private void movePlayer(GroundHit ground) {
        location = player.getEyeLocation();
        location.setPitch(0);

        Vector forward = location.getDirection().setY(0).normalize();
        GroundHit ahead = findGroundAt(player.getLocation().add(forward.clone().multiply(speed * VERTICAL_RESPONSE_TICKS)));
        GroundHit followed = ahead == null ? ground : ahead;
        double verticalVelocity = followed == null
                ? 0
                : clamp((rideHeight - followed.distance) / VERTICAL_RESPONSE_TICKS, -maxVerticalSpeed(), maxVerticalSpeed());
        Vector velocity = forward.clone().multiply(speed).setY(verticalVelocity);

        if (isPathBlocked(velocity.clone().setY(0))) {
            remove();
            return;
        }

        List<Location> waveLocations = rideWave(forward);
        pushEntities(waveLocations);
        GeneralMethods.setVelocity(this, player, velocity);
        player.setFallDistance(0);
    }

    private GroundHit findGround(Location feet) {
        Vector forward = getHorizontalDirection();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        double probeDistance = Math.max(player.getBoundingBox().getWidthX(), player.getBoundingBox().getWidthZ()) * GROUND_PROBE_WIDTH;

        GroundHit closest = findGroundAt(feet);
        closest = closerGround(closest, findGroundAt(feet.clone().add(right.clone().multiply(probeDistance))));
        return closerGround(closest, findGroundAt(feet.clone().subtract(right.multiply(probeDistance))));
    }

    private double maxVerticalSpeed() {
        return Math.max(MAX_VERTICAL_SPEED, speed);
    }

    private Vector getHorizontalDirection() {
        Location direction = player.getEyeLocation();
        direction.setPitch(0);
        return direction.getDirection().normalize();
    }

    private GroundHit findGroundAt(Location probe) {
        return findGroundAt(probe, GROUND_REACH_UP);
    }

    private GroundHit findGroundAt(Location probe, double reachUp) {
        if (probe.getWorld() == null || maxGroundDistance < 0) {
            return null;
        }

        double highestSurface = probe.getY() + reachUp;
        double lowestSurface = probe.getY() - maxGroundDistance;
        int highestBlockY = Math.min(probe.getWorld().getMaxHeight() - 1, floor(highestSurface));
        int lowestBlockY = Math.max(probe.getWorld().getMinHeight(), floor(lowestSurface));
        GroundHit closest = null;

        for (int y = highestBlockY; y >= lowestBlockY; y--) {
            Block block = probe.getWorld().getBlockAt(floor(probe.getX()), y, floor(probe.getZ()));
            if (block.isPassable()) {
                continue;
            }

            for (BoundingBox localBox : block.getCollisionShape().getBoundingBoxes()) {
                BoundingBox box = toWorldBox(block, localBox);
                if (!containsColumn(box, probe.getX(), probe.getZ())) {
                    continue;
                }
                double surfaceY = box.getMaxY();
                if (surfaceY > highestSurface + COLLISION_EPSILON || surfaceY < lowestSurface - COLLISION_EPSILON) {
                    continue;
                }
                closest = closerGround(closest, new GroundHit(block, probe.getY() - surfaceY, surfaceY, false));
            }
        }

        for (Map.Entry<Block, RidingSurface> entry : ridingSurfaces.entrySet()) {
            Block block = entry.getKey();
            if (block.getWorld() != probe.getWorld()) {
                continue;
            }

            for (BoundingBox box : entry.getValue().collisionBoxes) {
                if (!containsColumn(box, probe.getX(), probe.getZ())) {
                    continue;
                }
                double surfaceY = box.getMaxY();
                if (surfaceY > highestSurface + COLLISION_EPSILON || surfaceY < lowestSurface - COLLISION_EPSILON) {
                    continue;
                }
                closest = closerGround(closest, new GroundHit(block, probe.getY() - surfaceY, surfaceY, true));
            }
        }

        return closest;
    }

    private GroundHit closerGround(GroundHit first, GroundHit second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return second.distance < first.distance ? second : first;
    }

    private boolean containsColumn(BoundingBox box, double x, double z) {
        return x >= box.getMinX() - COLLISION_EPSILON
                && x <= box.getMaxX() + COLLISION_EPSILON
                && z >= box.getMinZ() - COLLISION_EPSILON
                && z <= box.getMaxZ() + COLLISION_EPSILON;
    }

    private boolean isPathBlocked(Vector horizontalMovement) {
        BoundingBox current = player.getBoundingBox();
        BoundingBox destination = current.clone().shift(horizontalMovement);
        BoundingBox path = current.clone().union(destination);
        return hasCollision(path);
    }

    private boolean hasCollision(BoundingBox bounds) {
        int minX = floor(bounds.getMinX());
        int maxX = floor(bounds.getMaxX() - COLLISION_EPSILON);
        int minY = Math.max(player.getWorld().getMinHeight(), floor(bounds.getMinY()));
        int maxY = Math.min(player.getWorld().getMaxHeight() - 1, floor(bounds.getMaxY() - COLLISION_EPSILON));
        int minZ = floor(bounds.getMinZ());
        int maxZ = floor(bounds.getMaxZ() - COLLISION_EPSILON);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = player.getWorld().getBlockAt(x, y, z);
                    for (BoundingBox localBox : block.getCollisionShape().getBoundingBoxes()) {
                        if (toWorldBox(block, localBox).overlaps(bounds)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static BoundingBox toWorldBox(Block block, BoundingBox box) {
        return box.clone().shift(block.getX(), block.getY(), block.getZ());
    }

    private List<Location> rideWave(Vector forward) {
        List<Location> waveLocations = new ArrayList<>(WAVE_LANE_COUNT);
        Location feet = player.getLocation();

        for (int lane = 0; lane < WAVE_LANE_COUNT; lane++) {
            Vector sideDirection = getWaveSideDirection(forward, lane).multiply(WAVE_LANE_DISTANCE);
            Location materialProbe = feet.clone().add(sideDirection)
                    .add(forward.clone().multiply(WAVE_VISUAL_DISTANCE));
            GroundHit sourceGround = findGroundAt(materialProbe, WAVE_VISUAL_DISTANCE);
            BlockData blockData = sourceGround == null ? null : getWaveBlockData(sourceGround);
            if (blockData == null) {
                continue;
            }

            Location visualLocation = materialProbe.clone();
            visualLocation.setY(Math.max(location.getY() - WAVE_VISUAL_EYE_OFFSET,
                    sourceGround.surfaceY + WAVE_SURFACE_CLEARANCE));
            if (isWavePathBlocked(visualLocation)) {
                continue;
            }

            Location terrainProbe = feet.clone().add(sideDirection)
                    .add(forward.clone().multiply(WAVE_TERRAIN_DISTANCE));
            GroundHit terrainGround = findGroundAt(terrainProbe, WAVE_TERRAIN_DISTANCE);
            if (terrainGround == null || (!terrainGround.virtual && !isEarthbendable(player, terrainGround.block))) {
                continue;
            }

            Block terrainBlock = terrainGround.block;
            if (RegionProtection.isRegionProtected(this, terrainBlock.getLocation())) {
                continue;
            }

            applyWaveTerrain(terrainBlock);
            new TempFallingBlock(visualLocation, blockData, new Vector(0, 0.25, 0), this, true);
            waveLocations.add(visualLocation);
        }

        return waveLocations;
    }

    private boolean isWavePathBlocked(Location visualLocation) {
        double halfWidth = WAVE_FALLING_BLOCK_SIZE / 2;
        BoundingBox spawnBounds = new BoundingBox(
                visualLocation.getX() - halfWidth,
                visualLocation.getY(),
                visualLocation.getZ() - halfWidth,
                visualLocation.getX() + halfWidth,
                visualLocation.getY() + WAVE_FALLING_BLOCK_SIZE,
                visualLocation.getZ() + halfWidth
        );
        BoundingBox pathBounds = spawnBounds.clone().union(
                spawnBounds.clone().shift(0, WAVE_FALLING_BLOCK_MAX_RISE, 0)
        );
        return hasCollision(pathBounds);
    }

    private BlockData getWaveBlockData(GroundHit sourceGround) {
        if (sourceGround.virtual) {
            RidingSurface ridingSurface = ridingSurfaces.get(sourceGround.block);
            return ridingSurface == null ? null : ridingSurface.blockData;
        }
        if (!isEarthbendable(player, sourceGround.block)
                || RegionProtection.isRegionProtected(this, sourceGround.block.getLocation())) {
            return null;
        }
        return sourceGround.block.getBlockData().clone();
    }

    private Vector getWaveSideDirection(Vector forward, int lane) {
        if (lane == 0) {
            return new Vector(-forward.getZ(), 0, forward.getX()).normalize();
        }
        if (lane == 1) {
            return new Vector(forward.getZ(), 0, -forward.getX()).normalize();
        }
        return new Vector();
    }

    private void applyWaveTerrain(Block block) {
        if (DensityShift.isPassiveSand(block)) {
            DensityShift.revertSand(block);
        }

        if (GeneralMethods.isSolid(block)) {
            RidingSurface ridingSurface = new RidingSurface(block);
            ridingBlocks.add(block);
            ridingSurfaces.put(block, ridingSurface);
        }
        waveTerrainExpiryTicks.put(block, waveTick + WAVE_SURFACE_RETURN_TICKS);
        new RegenTempBlock(block, Material.AIR, Material.AIR.createBlockData(), WAVE_REVERT_FALLBACK, true, reverted -> {
            ridingBlocks.remove(reverted);
            ridingSurfaces.remove(reverted);
            waveTerrainExpiryTicks.remove(reverted);
        });
    }

    private void revertExpiredWaveTerrain() {
        Iterator<Map.Entry<Block, Long>> iterator = waveTerrainExpiryTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Block, Long> entry = iterator.next();
            if (entry.getValue() > waveTick) {
                continue;
            }

            Block block = entry.getKey();
            iterator.remove();
            revertWaveTerrain(block);
        }
    }

    private void revertWaveTerrain(Block block) {
        RegenTempBlock.revert(block);
        ridingBlocks.remove(block);
        ridingSurfaces.remove(block);
    }

    private void pushEntities(List<Location> waveLocations) {
        if (waveLocations.isEmpty()) {
            return;
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (Location waveLocation : waveLocations) {
            minX = Math.min(minX, waveLocation.getX());
            minY = Math.min(minY, waveLocation.getY());
            minZ = Math.min(minZ, waveLocation.getZ());
            maxX = Math.max(maxX, waveLocation.getX());
            maxY = Math.max(maxY, waveLocation.getY());
            maxZ = Math.max(maxZ, waveLocation.getZ());
        }

        BoundingBox query = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ).expand(WAVE_PUSH_RADIUS);
        for (Entity entity : player.getWorld().getNearbyEntities(query)) {
            if (!(entity instanceof LivingEntity) || entity.getEntityId() == player.getEntityId()) {
                continue;
            }
            if (entity instanceof Player && Commands.invincible.contains(entity.getName())) {
                continue;
            }
            if (RegionProtection.isRegionProtected(this, entity.getLocation()) || !isInsideWave(entity, waveLocations)) {
                continue;
            }
            GeneralMethods.setVelocity(this, entity, new Vector(0, WAVE_PUSH_VELOCITY, 0));
        }
    }

    private boolean isInsideWave(Entity entity, List<Location> waveLocations) {
        for (Location waveLocation : waveLocations) {
            if (waveLocation.distanceSquared(entity.getLocation()) <= WAVE_PUSH_RADIUS * WAVE_PUSH_RADIUS) {
                return true;
            }
        }
        return false;
    }

    public static void handleDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || GeneralMethods.isFakeEvent(event)) {
            return;
        }

        EarthSurf earthSurf = getAbility(player, EarthSurf.class);
        if (earthSurf != null && earthSurf.removeOnDamage && hasEffectiveDamage(event)) {
            earthSurf.remove();
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean hasEffectiveDamage(EntityDamageEvent event) {
        return event.getFinalDamage() > 0
                || event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION) < 0;
    }

    private long calculateCooldown() {
        if (!cooldownScaled || !durationEnabled || duration <= 0) {
            return maximumCooldown;
        }

        double usage = clamp((System.currentTimeMillis() - getStartTime()) / (double) duration, 0, 1);
        return Math.round(minimumCooldown + (maximumCooldown - minimumCooldown) * usage);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    @Override
    public void remove() {
        boolean addCooldown = isStarted() && !isRemoved() && cooldownEnabled && player != null && player.isOnline();

        if (player != null) {
            this.flightHandler.removeInstance(player, this.getName());
        }
        for (Block block : new ArrayList<>(waveTerrainExpiryTicks.keySet())) {
            revertWaveTerrain(block);
        }
        waveTerrainExpiryTicks.clear();
        ridingBlocks.clear();
        ridingSurfaces.clear();

        if (addCooldown) {
            bPlayer.addCooldown(this, calculateCooldown());
        }

        super.remove();
    }

    @Override
    public long getCooldown() {
        return maximumCooldown;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public String getName() {
        return "EarthSurf";
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    @Override
    public String getDescription() {
        ConfigurationSection config = JedCoreConfig.getConfig(this.player);
        return "* JedCore Addon *\n" + config.getString("Abilities.Earth.EarthSurf.Description");
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    public void setCooldown(long cooldown) {
        this.maximumCooldown = cooldown;
    }

    public long getMinimumCooldown() {
        return minimumCooldown;
    }

    public void setMinimumCooldown(long minimumCooldown) {
        this.minimumCooldown = minimumCooldown;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public boolean isCooldownEnabled() {
        return cooldownEnabled;
    }

    public void setCooldownEnabled(boolean cooldownEnabled) {
        this.cooldownEnabled = cooldownEnabled;
    }

    public boolean isCooldownScaled() {
        return cooldownScaled;
    }

    public void setCooldownScaled(boolean cooldownScaled) {
        this.cooldownScaled = cooldownScaled;
    }

    public boolean isDurationEnabled() {
        return durationEnabled;
    }

    public void setDurationEnabled(boolean durationEnabled) {
        this.durationEnabled = durationEnabled;
    }

    public boolean isRemoveOnDamage() {
        return removeOnDamage;
    }

    public void setRemoveOnDamage(boolean removeOnDamage) {
        this.removeOnDamage = removeOnDamage;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public double getRideHeight() {
        return rideHeight;
    }

    public void setRideHeight(double rideHeight) {
        this.rideHeight = rideHeight;
    }

    public double getMaxGroundDistance() {
        return maxGroundDistance;
    }

    public void setMaxGroundDistance(double maxGroundDistance) {
        this.maxGroundDistance = maxGroundDistance;
    }

    public int getTerrainGraceTicks() {
        return terrainGraceTicks;
    }

    public void setTerrainGraceTicks(int terrainGraceTicks) {
        this.terrainGraceTicks = terrainGraceTicks;
    }

    public Set<Block> getRidingBlocks() {
        return ridingBlocks;
    }

    @Override
    public void load() {}

    @Override
    public void stop() {}

    @Override
    public boolean isEnabled() {
        ConfigurationSection config = JedCoreConfig.getConfig(this.player);
        return config.getBoolean("Abilities.Earth.EarthSurf.Enabled");
    }

    private static class GroundHit {
        private final Block block;
        private final double distance;
        private final double surfaceY;
        private final boolean virtual;

        private GroundHit(Block block, double distance, double surfaceY, boolean virtual) {
            this.block = block;
            this.distance = distance;
            this.surfaceY = surfaceY;
            this.virtual = virtual;
        }
    }

    private static class RidingSurface {
        private final BlockData blockData;
        private final List<BoundingBox> collisionBoxes;

        private RidingSurface(Block block) {
            this.blockData = block.getBlockData().clone();
            this.collisionBoxes = new ArrayList<>();
            for (BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
                collisionBoxes.add(toWorldBox(block, box));
            }
        }
    }
}
