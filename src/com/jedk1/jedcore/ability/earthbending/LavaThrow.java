package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class LavaThrow extends LavaAbility implements AddonAbility {
	private static final long BLOCK_REGEN_DELAY = 200;
	private static final double MINIMUM_DIRECTION_LENGTH_SQUARED = 1.0E-8;
	private static final double COLLISION_RADIUS = 2.0;
	private static final double SOURCE_SEARCH_RADIUS = 3.0;
	private static final String SNEAK_SELECT_PATH = "Abilities.Earth.LavaThrow.Source.SneakSelect";

	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute(Attribute.RANGE)
	private int range;
	@Attribute(Attribute.DAMAGE)
	private double damage;
	private boolean resetNoDamageTicks;
	@Attribute(Attribute.SELECT_RANGE)
	private int sourceRange;
	private boolean sneakSelect;
	@Attribute("MaxShots")
	private int shotMax;
	@Attribute(Attribute.FIRE_TICK)
	private int fireTicks;
	@Attribute("CurveFactor")
	private double curveFactor;

	private Location location;
	private int shots;
	private Block selectedSource;
	private JCMethods.MovedEarthLease sourceLease;
	private boolean cooldownApplied;

	private final List<Blast> blasts = new ArrayList<>();

	public LavaThrow(Player player) {
		super(player);

		if (player == null || bPlayer == null || !bPlayer.canBend(this) || !bPlayer.canLavabend()) {
			return;
		}

		setFields();

		if (shotMax <= 0) {
			return;
		}

		location = player.getLocation();
		location.setPitch(0);

		if (sneakSelect) {
			if (!prepare()) {
				return;
			}

			try {
				player.getWorld().playSound(selectedSource.getLocation(), Sound.ITEM_BUCKET_FILL_LAVA, 1.0f, 1.0f);
				start();
			} finally {
				if (!isStarted()) {
					releaseSourceLease();
				}
			}

			return;
		}

		Block source = findSourceBlock();

		if (source == null) {
			return;
		}

		start();

		if (isStarted()) {
			fire(source);
		}
	}

	public static void select(Player player) {
		if (!isSneakSelectEnabled(player) || getAbility(player, LavaThrow.class) != null) {
			return;
		}

		new LavaThrow(player);
	}

	public static void shoot(Player player) {
		LavaThrow lavaThrow = getAbility(player, LavaThrow.class);

		if (lavaThrow != null) {
			lavaThrow.createBlast();
			return;
		}

		if (!isSneakSelectEnabled(player)) {
			new LavaThrow(player);
		}
	}

	private static boolean isSneakSelectEnabled(Player player) {
		return JedCoreConfig.getConfig(player).getBoolean(SNEAK_SELECT_PATH);
	}

	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);

		cooldown = config.getLong("Abilities.Earth.LavaThrow.Cooldown");
		range = config.getInt("Abilities.Earth.LavaThrow.Range");
		damage = config.getDouble("Abilities.Earth.LavaThrow.Damage");
		resetNoDamageTicks = config.getBoolean("Abilities.Earth.LavaThrow.ResetNoDamageTicks");
		sourceRange = config.getInt("Abilities.Earth.LavaThrow.Source.Range");
		sneakSelect = config.getBoolean(SNEAK_SELECT_PATH);
		shotMax = config.getInt("Abilities.Earth.LavaThrow.MaxShots");
		fireTicks = config.getInt("Abilities.Earth.LavaThrow.FireTicks");
		curveFactor = Math.max(0.0, Math.min(1.0, config.getDouble("Abilities.Earth.LavaThrow.CurveFactor")));
	}

	@Override
	public void progress() {
		if (!getName().equalsIgnoreCase(bPlayer.getBoundAbilityName())) {
			remove();
			if (shots > 0) applyCooldown();
			return;
		}

		if (!player.getWorld().equals(location.getWorld())) {
			remove();
			if (shots > 0) applyCooldown();
			return;
		}

		if (sneakSelect && !isSourceUsable()) {
			remove();
			if (shots > 0) applyCooldown();
			return;
		}

		if (blasts.isEmpty() && (!sneakSelect || shots >= shotMax)) {
			remove();
			if (shots > 0) applyCooldown();
			return;
		}

		if (sneakSelect) {
			selectedSource.getWorld().spawnParticle(Particle.FLAME, selectedSource.getLocation(), 2, 0.3, 1.0, 0.3, 0.05);
			selectedSource.getWorld().spawnParticle(Particle.LAVA, selectedSource.getLocation(), 2, 0.2, 0.2, 0.2, 0);
		}

		handleBlasts();
	}

	private boolean isSourceUsable() {
		if (!LavaAbility.isLava(selectedSource)) {
			return false;
		}

		return player.getLocation().distance(selectedSource.getLocation()) < sourceRange;
	}

	private Block findSourceBlock() {
		Location searchCenter = player.getLocation();
		searchCenter.setPitch(0);
		searchCenter.add(searchCenter.getDirection().multiply(sourceRange));

		List<Block> candidates = GeneralMethods.getBlocksAroundPoint(searchCenter, SOURCE_SEARCH_RADIUS);
		Collections.shuffle(candidates);

		for (Block candidate : candidates) {
			if (LavaAbility.isLava(candidate)
					&& !RegionProtection.isRegionProtected(this, candidate.getLocation())) {
				return candidate;
			}
		}

		return null;
	}

	private boolean prepare() {
		Block targetBlock = getTargetLavaBlock(sourceRange);

		if (targetBlock != null
				&& !RegionProtection.isRegionProtected(this, targetBlock.getLocation())) {
			sourceLease = JCMethods.protectMovedEarth(targetBlock);
			selectedSource = targetBlock;
			return true;
		}

		return false;
	}

	public Block getTargetLavaBlock(int maxDistance) {
		Location eyeLocation = player.getEyeLocation();
		Vector direction = eyeLocation.getDirection();
		World world = player.getWorld();

		RayTraceResult result = world.rayTraceBlocks(
				eyeLocation, direction, maxDistance,
				FluidCollisionMode.ALWAYS, true
		);

		if (result != null) {
			Block hitBlock = result.getHitBlock();
			if (LavaAbility.isLava(hitBlock)) {
				return hitBlock;
			}
		}
		return null;
	}

	public void createBlast() {
		fire(sneakSelect ? selectedSource : findSourceBlock());
	}

	private void fire(Block source) {
		if (source == null || shots >= shotMax || !player.getWorld().equals(source.getWorld())) {
			return;
		}

		shots++;

		if (shots >= shotMax) {
			applyCooldown();
		}

		Location origin = source.getLocation().clone().add(0, 2, 0);
		player.getWorld().playSound(origin, Sound.ITEM_BUCKET_EMPTY_LAVA, 1.0f, 1.0f);
		double viewRange = range + origin.distance(player.getEyeLocation());
		Location viewTarget = GeneralMethods.getTargetedLocation(player, viewRange, Material.WATER, Material.LAVA);
		Vector direction = viewTarget.clone().subtract(origin).toVector().normalize();
		Location head = origin.clone();

		head.setDirection(direction);
		blasts.add(new Blast(origin, head));

		Block above = source.getRelative(BlockFace.UP);

		if (!LavaAbility.isLava(above) && !RegionProtection.isRegionProtected(this, above.getLocation())) {
			new RegenTempBlock(above, Material.LAVA, Material.LAVA.createBlockData(), BLOCK_REGEN_DELAY);
		}
	}

	public void handleBlasts() {
		Iterator<Blast> iterator = blasts.iterator();

		while (iterator.hasNext()) {
			Blast blast = iterator.next();
			Location current = blast.head;

			if (current.distance(blast.origin) > range) {
				iterator.remove();
				continue;
			}

			if (GeneralMethods.isSolid(current.getBlock())) {
				iterator.remove();
				continue;
			}

			Vector currentDirection = current.getDirection();
			Vector playerLookDirection = player.getEyeLocation().getDirection();

			Vector curveVector = playerLookDirection.clone()
					.subtract(currentDirection)
					.multiply(curveFactor);

			Vector newDirection = currentDirection.clone().add(curveVector);

			if (newDirection.lengthSquared() < MINIMUM_DIRECTION_LENGTH_SQUARED) {
				newDirection = currentDirection;
			} else {
				newDirection.normalize();
			}

			Location next = current.clone();
			next.setDirection(newDirection);
			next.add(newDirection);

			if (!RegionProtection.isRegionProtected(this, current)) {
				new RegenTempBlock(current.getBlock(), Material.LAVA, Material.LAVA.createBlockData(bd -> ((Levelled) bd).setLevel(0)), BLOCK_REGEN_DELAY);
			}

			next.getWorld().spawnParticle(Particle.LAVA, next, 1, Math.random(), Math.random(), Math.random(), 0);

			boolean hit = false;

			for (Entity entity : GeneralMethods.getEntitiesAroundPoint(current, COLLISION_RADIUS)) {
				if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId() && !RegionProtection.isRegionProtected(this, entity.getLocation()) && !((entity instanceof Player) && Commands.invincible.contains(((Player) entity).getName()))) {
					LivingEntity target = (LivingEntity) entity;
					if (resetNoDamageTicks) {
						target.setNoDamageTicks(0);
					}
					DamageHandler.damageEntity(target, damage, this);
					if (resetNoDamageTicks) {
						target.setNoDamageTicks(0);
					}
					target.setFireTicks(this.fireTicks);

					hit = true;
					break;
				}
			}

			if (hit) {
				iterator.remove();
				continue;
			}

			blast.head = next;
		}
	}

	@Override
	public void remove() {
		releaseSourceLease();
		super.remove();
	}

	private void releaseSourceLease() {
		if (sourceLease != null) {
			sourceLease.close();
			sourceLease = null;
		}
	}

	private void applyCooldown() {
		if (cooldownApplied || bPlayer == null) {
			return;
		}

		cooldownApplied = true;
		bPlayer.addCooldown(this);
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
		return "LavaThrow";
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
		return "* JedCore Addon *\n" + config.getString("Abilities.Earth.LavaThrow.Description");
	}

	public void setCooldown(long cooldown) {
		this.cooldown = cooldown;
	}

	public int getRange() {
		return range;
	}

	public void setRange(int range) {
		this.range = range;
	}

	public double getDamage() {
		return damage;
	}

	public void setDamage(double damage) {
		this.damage = damage;
	}

	public int getSourceRange() {
		return sourceRange;
	}

	public void setSourceRange(int sourceRange) {
		this.sourceRange = sourceRange;
	}

	public boolean isSneakSelect() {
		return sneakSelect;
	}

	public void setSneakSelect(boolean sneakSelect) {
		this.sneakSelect = sneakSelect;
	}

	public int getShotMax() {
		return shotMax;
	}

	public void setShotMax(int shotMax) {
		this.shotMax = shotMax;
	}

	public int getFireTicks() {
		return fireTicks;
	}

	public void setFireTicks(int fireTicks) {
		this.fireTicks = fireTicks;
	}

	public void setLocation(Location location) {
		this.location = location;
	}

	public int getShots() {
		return shots;
	}

	public void setShots(int shots) {
		this.shots = shots;
	}

	public List<Location> getBlasts() {
		List<Location> heads = new ArrayList<>(blasts.size());

		for (Blast blast : blasts) {
			heads.add(blast.head.clone());
		}

		return heads;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Earth.LavaThrow.Enabled");
	}

	private static class Blast {
		private final Location origin;
		private Location head;

		private Blast(Location origin, Location head) {
			this.origin = origin;
			this.head = head;
		}
	}
}
