package com.jedk1.jedcore.ability.avatar;

import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AvatarAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public class SpiritBeam extends AvatarAbility implements AddonAbility {

	private static final double BEAM_STEP = 0.5;
	private static final double LIGHT_SPACING = 4.0;
	private static final String PARTICLE_COLOR = "#A020F0";
	private static final BlockData PARTICLE_BLOCK_DATA = Material.NETHER_PORTAL.createBlockData();
	private static final BlockData AIR_BLOCK_DATA = Material.AIR.createBlockData();

    private Location location;
	private boolean damagesBlocks;
	private long regen;
	private boolean avatarOnly;
	private double entityCollisionRadius;
	private int fireTicks;

	@Attribute(Attribute.DURATION)
	private long duration;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute(Attribute.RANGE)
	private double range;
	@Attribute(Attribute.DAMAGE)
	private double damage;
	@Attribute(Attribute.RADIUS)
	private double radius;

	public SpiritBeam(Player player) {
		super(player);

		if (this.player == null || bPlayer == null) return;
		if (bPlayer.isOnCooldown(this)) return;

		setFields();

		if (avatarOnly && !bPlayer.isAvatarState()) return;

		start();
	}

	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		
		duration = config.getLong("Abilities.Avatar.SpiritBeam.Duration");
		cooldown = config.getLong("Abilities.Avatar.SpiritBeam.Cooldown");
		damage = config.getDouble("Abilities.Avatar.SpiritBeam.Damage");
		range = config.getDouble("Abilities.Avatar.SpiritBeam.Range");
		entityCollisionRadius = config.getDouble("Abilities.Avatar.SpiritBeam.EntityCollisionRadius");
		fireTicks = config.getInt("Abilities.Avatar.SpiritBeam.FireTicks");
		avatarOnly = config.getBoolean("Abilities.Avatar.SpiritBeam.AvatarStateOnly");
		damagesBlocks = config.getBoolean("Abilities.Avatar.SpiritBeam.BlockDamage.Enabled");
		regen = config.getLong("Abilities.Avatar.SpiritBeam.BlockDamage.Regen");
		radius = config.getDouble("Abilities.Avatar.SpiritBeam.BlockDamage.Radius");
	}

	@Override
	public void progress() {
		if (player.isDead() || !player.isOnline()) {
			remove();
			return;
		}

		if (!bPlayer.canBendIgnoreBindsCooldowns(this)) {
			bPlayer.addCooldown(this);
			remove();
			return;
		}

		if (System.currentTimeMillis() > getStartTime() + duration) {
			bPlayer.addCooldown(this);
			remove();
			return;
		}

		if (!player.isSneaking()) {
			bPlayer.addCooldown(this);
			remove();
			return;
		}

		if (avatarOnly && !bPlayer.isAvatarState()) {
			bPlayer.addCooldown(this);
			remove();
			return;
		}

		createBeam();
	}

	private void createBeam() {
		Location origin = player.getLocation().add(0, 1.2, 0);
		Vector beamDirection = origin.getDirection().normalize();
		BeamPath beamPath = traceBeam(origin, beamDirection);

		location = beamPath.locations.isEmpty() ? origin : beamPath.locations.get(beamPath.locations.size() - 1);
		displayBeam(beamPath.locations);
		damageNearbyEntities(origin, beamDirection, beamPath.damageLength);

		if (beamPath.impactBlock != null) {
			handleBlockCollision(beamPath.impactLocation, beamPath.impactBlock);
		}
	}

	private BeamPath traceBeam(Location origin, Vector beamDirection) {
		double beamLength = Math.max(0, range);
		if (beamLength == 0) {
			return new BeamPath(new ArrayList<>(), 0, null, null);
		}

		Block impactBlock = null;
		Location impactLocation = null;
		RayTraceResult blockHit = origin.getWorld().rayTraceBlocks(
				origin, beamDirection, beamLength, FluidCollisionMode.NEVER, true
		);

		if (blockHit != null && blockHit.getHitBlock() != null) {
			impactBlock = blockHit.getHitBlock();
			impactLocation = blockHit.getHitPosition().toLocation(origin.getWorld());
			beamLength = origin.distance(impactLocation);
		}

		List<Location> locations = new ArrayList<>();
		double damageLength = 0;
		int steps = (int) Math.ceil(beamLength / BEAM_STEP);

		for (int step = 1; step <= steps; step++) {
			double distance = Math.min(step * BEAM_STEP, beamLength);
			Location current = origin.clone().add(beamDirection.clone().multiply(distance));
			boolean impact = impactBlock != null && step == steps;
			Location protectionLocation = impact ? impactBlock.getLocation() : current;

			if (isBeamObstructed(protectionLocation)) {
				impactBlock = null;
				impactLocation = null;
				break;
			}

			locations.add(current);
			damageLength = impact ? Math.max(0, distance - 0.01) : distance;
		}

		return new BeamPath(locations, damageLength, impactLocation, impactBlock);
	}

	private boolean isBeamObstructed(Location location) {
		return RegionProtection.isRegionProtected(player, location, this);
	}

	private void displayBeam(List<Location> locations) {
		int lightInterval = Math.max(1, (int) Math.ceil(LIGHT_SPACING / BEAM_STEP));

		for (int i = 0; i < locations.size(); i++) {
			Location beamLocation = locations.get(i);
			displayBeamParticles(beamLocation);

			if (i % lightInterval == 0 || i == locations.size() - 1) {
				JCMethods.emitLight(beamLocation);
			}
		}
	}

	private void displayBeamParticles(Location location) {
		JCMethods.displayColoredParticles(PARTICLE_COLOR, location, 1, 0f, 0f, 0f, 0f);
		float randomOffset = (float) Math.random() / 3;
		location.getWorld().spawnParticle(
				Particle.BLOCK_CRACK, location, 1, randomOffset, randomOffset, randomOffset, 0.1F, PARTICLE_BLOCK_DATA
		);
	}

	private void damageNearbyEntities(Location origin, Vector beamDirection, double beamLength) {
		if (damage <= 0 || beamLength <= 0) {
			return;
		}

		double collisionRadius = Math.max(0, entityCollisionRadius);
		Vector originVector = origin.toVector();
		Vector endVector = originVector.clone().add(beamDirection.clone().multiply(beamLength));
		BoundingBox beamBounds = BoundingBox.of(originVector, endVector).expand(collisionRadius);

		for (Entity entity : origin.getWorld().getNearbyEntities(beamBounds)) {
			if (!(entity instanceof LivingEntity livingEntity) || entity.getEntityId() == player.getEntityId()) {
				continue;
			}

			BoundingBox entityBounds = entity.getBoundingBox().expand(collisionRadius);
			if (entityBounds.rayTrace(originVector, beamDirection, beamLength) == null
					|| isBeamObstructed(entity.getLocation())
					|| !hasClearLineOfSight(origin, livingEntity)) {
				continue;
			}

			if (fireTicks > 0) {
				livingEntity.setFireTicks(Math.max(livingEntity.getFireTicks(), fireTicks));
			}
			DamageHandler.damageEntity(livingEntity, damage, this);
		}
	}

	private boolean hasClearLineOfSight(Location origin, LivingEntity entity) {
		Vector target = entity.getBoundingBox().getCenter();
		Vector difference = target.clone().subtract(origin.toVector());
		double distance = difference.length();

		if (distance == 0) {
			return true;
		}

		RayTraceResult obstruction = origin.getWorld().rayTraceBlocks(
				origin, difference.normalize(), distance, FluidCollisionMode.NEVER, true
		);
		return obstruction == null;
	}

	private void handleBlockCollision(Location impactLocation, Block impactBlock) {
		impactLocation.getWorld().createExplosion(impactLocation, 0F);
		if (damagesBlocks) {
			damageBlocksInRadius(impactBlock.getLocation());
		}
	}

	private void damageBlocksInRadius(Location center) {
		double blockRadius = Math.max(0, radius);
		double radiusSquared = blockRadius * blockRadius;

		if (blockRadius <= 0) {
			return;
		}

		for (Block block : GeneralMethods.getBlocksAroundPoint(center, blockRadius)) {
			if (block.getLocation().distanceSquared(center) < radiusSquared
					&& !block.getType().isAir()
					&& !isBeamObstructed(block.getLocation())
					&& !JCMethods.isUnbreakable(block)) {
				new RegenTempBlock(block, Material.AIR, AIR_BLOCK_DATA, regen, false);
			}
		}
	}

	@Override
	public long getCooldown() {
		return cooldown;
	}

	@Override
	public Location getLocation() {
		return location;
	}

	@Override
	public String getName() {
		return "SpiritBeam";
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
		return "* JedCore Addon *\n" + config.getString("Abilities.Avatar.SpiritBeam.Description");
	}

	public long getDuration() {
		return duration;
	}

	public void setDuration(long duration) {
		this.duration = duration;
	}

	public double getRange() {
		return range;
	}

	public void setRange(double range) {
		this.range = range;
	}

	public boolean isAvatarOnly() {
		return avatarOnly;
	}

	public void setAvatarOnly(boolean avatarOnly) {
		this.avatarOnly = avatarOnly;
	}

	public double getDamage() {
		return damage;
	}

	public void setDamage(double damage) {
		this.damage = damage;
	}

	public double getEntityCollisionRadius() {
		return entityCollisionRadius;
	}

	public void setEntityCollisionRadius(double entityCollisionRadius) {
		this.entityCollisionRadius = entityCollisionRadius;
	}

	public int getFireTicks() {
		return fireTicks;
	}

	public void setFireTicks(int fireTicks) {
		this.fireTicks = fireTicks;
	}

	public boolean damagesBlocks() {
		return damagesBlocks;
	}

	public void setDamagesBlocks(boolean blockdamage) {
		this.damagesBlocks = blockdamage;
	}

	public long getRegen() {
		return regen;
	}

	public void setRegen(long regen) {
		this.regen = regen;
	}

	public double getRadius() {
		return radius;
	}

	public void setRadius(double radius) {
		this.radius = radius;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		return JedCoreConfig.isAbilityEnabled(this.player, "Abilities.Avatar.SpiritBeam.Enabled");
	}

	private static class BeamPath {

		private final List<Location> locations;
		private final double damageLength;
		private final Location impactLocation;
		private final Block impactBlock;

		private BeamPath(List<Location> locations, double damageLength, Location impactLocation, Block impactBlock) {
			this.locations = locations;
			this.damageLength = damageLength;
			this.impactLocation = impactLocation;
			this.impactBlock = impactBlock;
		}
	}
}
