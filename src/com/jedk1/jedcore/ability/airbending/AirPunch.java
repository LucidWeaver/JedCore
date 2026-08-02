package com.jedk1.jedcore.ability.airbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.collision.Sphere;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class AirPunch extends AirAbility implements AddonAbility {

	private final List<Shot> activeShots = new ArrayList<>();

	private int shots;
	private long lastShotTime;

	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	private long threshold;
	private boolean resetNoDamageTicks;
	@Attribute(Attribute.RANGE)
	private double range;
	@Attribute(Attribute.DAMAGE)
	private double damage;
	@Attribute("CollisionRadius")
	private double entityCollisionRadius;
	@Attribute("Speed")
	private double speed;

	public AirPunch(Player player) {
		super(player);

		if (!bPlayer.canBend(this)) {
			return;
		}

		if (hasAbility(player, AirPunch.class)) {
			AirPunch ap = getAbility(player, AirPunch.class);
			ap.createShot();
			return;
		}

		setFields();

		if (!hasValidTravelSettings()) {
			return;
		}

		start();

		if (!isRemoved() && !hasValidTravelSettings()) {
			remove();
			return;
		}

		if (!isRemoved()) createShot();
	}

	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);

		cooldown = config.getLong("Abilities.Air.AirPunch.Cooldown");
		threshold = config.getLong("Abilities.Air.AirPunch.Threshold");
		shots = config.getInt("Abilities.Air.AirPunch.Shots");
		range = config.getDouble("Abilities.Air.AirPunch.Range");
		damage = config.getDouble("Abilities.Air.AirPunch.Damage");
		resetNoDamageTicks = config.getBoolean("Abilities.Air.AirPunch.ResetNoDamageTicks");
		entityCollisionRadius = config.getDouble("Abilities.Air.AirPunch.EntityCollisionRadius");
		speed = config.getDouble("Abilities.Air.AirPunch.Speed");
	}

	@Override
	public void progress() {
		if (player.isDead() || !player.isOnline()) {
			remove();
			return;
		}

		if (!hasValidTravelSettings() || hasShotsOutsidePlayerWorld()) {
			activeShots.clear();
			prepareRemove();
			return;
		}

		progressShots();

		if (!bPlayer.canBendIgnoreBindsCooldowns(this)) {
			prepareRemove();
			return;
		}

		if (shots == 0 || System.currentTimeMillis() > lastShotTime + threshold) {
			prepareRemove();
		}
	}

	private void prepareRemove() {
		if (player.isOnline() && !bPlayer.isOnCooldown(this)) {
			bPlayer.addCooldown(this);
		}

		if (activeShots.isEmpty()) {
			remove();
		}
	}

	private void createShot() {
		if (shots >= 1) {
			lastShotTime = System.currentTimeMillis();
			shots--;

			Location origin = player.getEyeLocation();
			Location start = origin.clone().add(origin.getDirection());

			if (!isPathBlocked(origin, start)) {
				activeShots.add(new Shot(start, 0D));
			}
		}
	}

	private void progressShots() {
		List<Shot> shotsToProgress = new ArrayList<>(activeShots);
		activeShots.clear();

		for (Shot shot : shotsToProgress) {
			ShotResult result = simulateShotProgression(shot.location(), shot.distance());

			if (result.active()) {
				activeShots.add(new Shot(result.newLoc(), result.newDist()));
			}
		}
	}

	private record Shot(Location location, double distance) {}

	private record ShotResult(Location newLoc, double newDist, boolean active) {}

	private ShotResult simulateShotProgression(Location startLoc, double startDist) {
		Location loc = startLoc.clone();
		double dist = startDist;

		for (int i = 0; i < 3; i++) {
			double nextDist = dist + speed;
			if (nextDist >= range) {
				return new ShotResult(loc, dist, false);
			}

			Location nextLoc = calculateNextLocation(loc);
			if (isPathBlocked(loc, nextLoc)) {
				return new ShotResult(loc, dist, false);
			}

			applyShotEffects(nextLoc);
			if (checkAndHandleCollision(nextLoc)) {
				return new ShotResult(loc, dist, false);
			}

			loc = nextLoc;
			dist = nextDist;
		}

		return new ShotResult(loc, dist, true);
	}

	private Location calculateNextLocation(Location currentLocation) {
		return currentLocation.clone().add(currentLocation.getDirection().clone().multiply(speed));
	}

	private boolean isPathBlocked(Location start, Location end) {
		if (start.getWorld() == null || end.getWorld() == null || start.getWorld() != end.getWorld()) {
			return true;
		}

		if (isPathBlocked(start) || isPathBlocked(end)) {
			return true;
		}

		Vector path = end.toVector().subtract(start.toVector());
		double distance = path.length();
		return distance > 0 && start.getWorld().rayTraceBlocks(start, path.normalize(), distance, FluidCollisionMode.ALWAYS, true) != null;
	}

	private boolean isPathBlocked(Location location) {
		return GeneralMethods.isSolid(location.getBlock()) || isWater(location.getBlock()) || RegionProtection.isRegionProtected(player, location, this);
	}

	private void applyShotEffects(Location location) {
		playAirbendingParticles(location, 2,  Math.random() / 5, Math.random() / 5, Math.random() / 5);
		playAirbendingSound(location);
	}

	private boolean checkAndHandleCollision(Location location) {
		return CollisionDetector.checkEntityCollisions(player, location.getWorld(), new Sphere(location.toVector(), entityCollisionRadius), entity -> {
			LivingEntity target = (LivingEntity) entity;
			if (resetNoDamageTicks) {
				target.setNoDamageTicks(0);
			}
			DamageHandler.damageEntity(target, damage, this);
			if (resetNoDamageTicks) {
				target.setNoDamageTicks(0);
			}
			return true;
		});
	}

	private boolean hasValidTravelSettings() {
		return Double.isFinite(speed) && speed > 0 && Double.isFinite(range) && range > 0;
	}

	private boolean hasShotsOutsidePlayerWorld() {
		for (Shot shot : activeShots) {
			if (shot.location().getWorld() != player.getWorld()) {
				return true;
			}
		}

		return false;
	}

	@Override
	public long getCooldown() {
		return cooldown;
	}

	@Override
	public double getCollisionRadius() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getDouble("Abilities.Air.AirPunch.AbilityCollisionRadius");
	}

	@Override
	public Location getLocation() {
		return null;
	}

	@Override
	public void handleCollision(Collision collision) {
		if (collision.isRemovingFirst()) {
			Location location = collision.getLocationFirst();
			Iterator<Shot> iterator = activeShots.iterator();

			while (iterator.hasNext()) {
				if (iterator.next().location().equals(location)) {
					iterator.remove();
					break;
				}
			}
		}
	}

	@Override
	public List<Location> getLocations() {
		List<Location> locations = new ArrayList<>(activeShots.size());

		for (Shot shot : activeShots) {
			locations.add(shot.location());
		}

		return locations;
	}

	@Override
	public String getName() {
		return "AirPunch";
	}

	@Override
	public boolean isHarmlessAbility() {
		return false;
	}

	@Override
	public boolean isSneakAbility() {
		return false;
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
		return "* JedCore Addon *\n" + config.getString("Abilities.Air.AirPunch.Description");
	}

	public long getThreshold() {
		return threshold;
	}

	public void setThreshold(long threshold) {
		this.threshold = threshold;
	}

	public double getRange() {
		return range;
	}

	public void setRange(double range) {
		this.range = range;
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

	public int getShots() {
		return shots;
	}

	public void setShots(int shots) {
		this.shots = shots;
	}

	public long getLastShotTime() {
		return lastShotTime;
	}

	public void setLastShotTime(long lastShotTime) {
		this.lastShotTime = lastShotTime;
	}

	public double getSpeed() {
		return speed;
	}

	public void setSpeed(double speed) {
		this.speed = speed;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Air.AirPunch.Enabled");
	}
}
