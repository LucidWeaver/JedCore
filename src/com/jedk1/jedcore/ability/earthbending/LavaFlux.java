package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.FireTick;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;

import com.projectkorra.projectkorra.util.TempBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class LavaFlux extends LavaAbility implements AddonAbility {

	@Attribute(Attribute.SPEED)
	private double speed;
	@Attribute(Attribute.RANGE)
	private int range;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute(Attribute.DURATION)
	private long duration;
	private long cleanup;
	@Attribute(Attribute.DAMAGE)
	private double damage;
	private boolean wave;
	private int stepInterval;
	private int fireTicks;

	private Location location;
	private int step;
	private int counter;
	private long time;
	private boolean complete;

	private double knockUp;
	private double knockBack;

	Random rand = new Random();

	private static final BlockData LAVA = Material.LAVA.createBlockData(bd -> ((Levelled)bd).setLevel(1));

	private final List<Step> flux = new ArrayList<>();

	private Map<Block, TempBlock> blocks = new HashMap<>();
	private Map<Block, TempBlock> above = new HashMap<>();

	private static class Step {
		private final Block block;
		private final BlockFace face;

		private Step(Block block, BlockFace face) {
			this.block = block;
			this.face = face;
		}
	}

	public LavaFlux(Player player) {
		super(player);

		if (!bPlayer.canBend(this) || !bPlayer.canLavabend()) {
			return;
		}

		setFields();
		recalculateAttributes();
		time = System.currentTimeMillis();
		if (prepareLine()) {
			start();
			if (!isRemoved()) {
				bPlayer.addCooldown(this);
			}
		}
	}

	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);

		stepInterval = config.getInt("Abilities.Earth.LavaFlux.Speed");
		if (stepInterval < 1) stepInterval = 1;
		speed = 1;
		fireTicks = config.getInt("Abilities.Earth.LavaFlux.FireTicks");
		range = config.getInt("Abilities.Earth.LavaFlux.Range");
		cooldown = config.getLong("Abilities.Earth.LavaFlux.Cooldown");
		duration = config.getLong("Abilities.Earth.LavaFlux.Duration");
		cleanup = config.getLong("Abilities.Earth.LavaFlux.Cleanup");
		damage = config.getDouble("Abilities.Earth.LavaFlux.Damage");
		wave = config.getBoolean("Abilities.Earth.LavaFlux.Wave");
		knockUp = config.getDouble("Abilities.Earth.LavaFlux.KnockUp");
		knockBack = config.getDouble("Abilities.Earth.LavaFlux.KnockBack");
	}

	private int getEffectiveStepInterval() {
		return Math.max(1, (int) Math.round(stepInterval / Math.max(speed, 0.05D)));
	}

	@Override
	public void progress() {
		if (player == null || !player.isOnline()) {
			remove();
			return;
		}
		if (!bPlayer.canBendIgnoreCooldowns(this)) {
			remove();
			return;
		}
		counter++;
		if (!complete) {
			if (counter % getEffectiveStepInterval() == 0) {
				for (int i = 0; i <= 2; i++) {
					step++;
					progressFlux();
					if (complete) {
						break;
					}
				}
			}
		} else if (System.currentTimeMillis() > time + duration) {
			for (TempBlock tb : blocks.values()) {
				if (!tb.isReverted()) tb.setType(Material.STONE);
			}
			remove();
		}
	}

	@Override
	public void remove() {
		boolean scheduleRevert = !isRemoved() && !complete;
		super.remove();

		if (scheduleRevert) {
			long revertDelay = Math.max(1L, duration + cleanup);
			for (TempBlock tb : blocks.values()) {
				if (!tb.isReverted()) tb.setRevertTime(revertDelay);
			}
			for (TempBlock tb : above.values()) {
				if (!tb.isReverted()) tb.setRevertTime(revertDelay);
			}
		}
	}

	private boolean prepareLine() {
		Vector direction = player.getEyeLocation().getDirection().setY(0).normalize();
		Vector blockdirection = direction.clone().setX(Math.round(direction.getX()));
		blockdirection = blockdirection.setZ(Math.round(direction.getZ()));
		Location origin = player.getLocation().add(0, -1, 0).add(blockdirection.multiply(2));
		if (!isEarthbendable(player, origin.getBlock())) {
			return false;
		}

		BlockFace cardinal = GeneralMethods.getCardinalDirection(blockdirection);
		BlockFace left = JCMethods.getLeftBlockFace(cardinal);
		BlockFace right = left.getOppositeFace();
		BlockIterator bi = new BlockIterator(player.getWorld(), origin.toVector(), direction, 0, range);
		Block previousColumn = origin.getBlock();
		int previousY = origin.getBlockY();
		int budget = range;

		while (bi.hasNext() && budget > 0) {
			Block b = bi.next();
			Block start = b.getWorld().getBlockAt(b.getX(), previousY, b.getZ());

			if (start.getY() <= start.getWorld().getMinHeight() || start.getY() >= start.getWorld().getMaxHeight()
					|| RegionProtection.isRegionProtected(this, start.getLocation())) {
				continue;
			}
			if (isWater(start)) break;

			Block surface = resolveSurface(start);
			if (surface == null) break;

			int dx = start.getX() - previousColumn.getX();
			int dz = start.getZ() - previousColumn.getZ();
			BlockFace forward = (dx == 0 && dz == 0) ? cardinal : GeneralMethods.getCardinalDirection(new Vector(dx, 0, dz));
			BlockFace backward = forward.getOppositeFace();
			int surfaceY = surface.getY();

			if (surfaceY > previousY) {
				for (int y = previousY + 1; y <= surfaceY && budget > 0; y++) {
					Block climbed = start.getWorld().getBlockAt(start.getX(), y, start.getZ());
					if (!isTransparent(climbed.getRelative(backward)) || !addStep(climbed, y == surfaceY ? BlockFace.UP : backward, left, right)) {
						return !flux.isEmpty();
					}
					budget--;
				}
			} else if (surfaceY < previousY) {
				for (int y = previousY - 1; y > surfaceY && budget > 0; y--) {
					Block dropped = start.getWorld().getBlockAt(previousColumn.getX(), y, previousColumn.getZ());
					if (!isTransparent(dropped.getRelative(forward)) || !addStep(dropped, forward, left, right)) {
						return !flux.isEmpty();
					}
					budget--;
				}
				if (budget > 0) {
					if (!addStep(surface, BlockFace.UP, left, right)) {
						return !flux.isEmpty();
					}
					budget--;
				}
			} else {
				if (!addStep(surface, BlockFace.UP, left, right)) {
					return !flux.isEmpty();
				}
				budget--;
			}

			previousColumn = start;
			previousY = surfaceY;
		}
		return true;
	}

	private boolean addStep(Block block, BlockFace face, BlockFace left, BlockFace right) {
		if (!isEarthbendable(block) || RegionProtection.isRegionProtected(this, block.getLocation())) {
			return false;
		}

		flux.add(new Step(block, face));
		expand(block.getRelative(left, 1), face);
		expand(block.getRelative(right, 1), face);
		return true;
	}

	private Block resolveSurface(Block block) {
		while (!isEarthbendable(block)) {
			if (block.getY() <= block.getWorld().getMinHeight()) {
				return null;
			}
			block = block.getRelative(BlockFace.DOWN);
		}

		while (!isTransparent(block.getRelative(BlockFace.UP))) {
			if (block.getY() + 1 >= block.getWorld().getMaxHeight()) {
				return null;
			}
			block = block.getRelative(BlockFace.UP);
		}

		return isEarthbendable(block) ? block : null;
	}

	private void progressFlux() {
		int limit = Math.min(step, flux.size() - 1);
		for (int index = 0; index <= limit; index++) {
			Step current = flux.get(index);
			Block block = current.block;

			if (!blocks.containsKey(block)) {
				blocks.put(block, JCMethods.createTempBlock(block, LAVA, this));
			}

			this.location = block.getLocation();
			if (index == step) {
				Block exposed = block.getRelative(current.face);
				Location center = exposed.getLocation().add(0.5, 0.5, 0.5);
				exposed.getWorld().spawnParticle(Particle.LAVA, center, 2, Math.random(), Math.random(), Math.random(), 0);
				applyDamageFromWave(center);

				if (current.face == BlockFace.UP && (isPlant(exposed) || isSnow(exposed))) {
					Block above2 = exposed.getRelative(BlockFace.UP);
					TempBlock tb = JCMethods.createTempBlock(exposed, Material.AIR.createBlockData(), this);
					this.above.put(exposed, tb);
					if (isPlant(above2) && above2.getBlockData() instanceof Bisected) {
						TempBlock tb2 = JCMethods.createTempBlock(above2, Material.AIR.createBlockData(), duration + cleanup + 30_000, this);
						tb.addAttachedBlock(tb2);
					}
				} else if (wave && isTransparent(exposed)) {
					JCMethods.createTempBlock(exposed, LAVA, getEffectiveStepInterval() * 150L, this);
				}
			}
		}

		if (step >= flux.size()) {
			wave = false;
			complete = true;
			time = System.currentTimeMillis();

			for (TempBlock tb : blocks.values()) {
				long revertDelay = duration + cleanup + rand.nextInt(1000);
				tb.setRevertTime(revertDelay);

				TempBlock aboveBlock = this.above.get(tb.getBlock().getRelative(BlockFace.UP));
				if (aboveBlock != null) {
					aboveBlock.setRevertTime(revertDelay);
				}
			}
		}
	}

	private void applyDamageFromWave(Location location) {
		for (Entity entity : GeneralMethods.getEntitiesAroundPoint(location, 1.5)) {
			if (!(entity instanceof LivingEntity) || entity.getEntityId() == player.getEntityId()) {
				continue;
			}
			if (RegionProtection.isRegionProtected(this, entity.getLocation())) {
				continue;
			}
			if (entity instanceof Player && Commands.invincible.contains(((Player) entity).getName())) {
				continue;
			}

			LivingEntity livingEntity = (LivingEntity) entity;

			DamageHandler.damageEntity(entity, damage, this);
			FireTick.set(entity, fireTicks);
			new FireDamageTimer(entity, player, this);

			Vector direction = livingEntity.getLocation().toVector().subtract(player.getLocation().toVector()).normalize();
			Vector knockbackVelocity = direction.multiply(knockBack).setY(knockUp);

			GeneralMethods.setVelocity(this, livingEntity, knockbackVelocity);
		}
	}

	private void expand(Block block, BlockFace face) {
		if (block == null || block.getY() <= block.getWorld().getMinHeight() || block.getY() >= block.getWorld().getMaxHeight()
				|| RegionProtection.isRegionProtected(this, block.getLocation())) {
			return;
		}
		if (isWater(block)) return;

		Block side = block;
		if (face == BlockFace.UP) {
			if (!isEarthbendable(side)) {
				side = side.getRelative(BlockFace.DOWN);
			} else if (!isTransparent(side.getRelative(BlockFace.UP))) {
				side = side.getRelative(BlockFace.UP);
			}
		}

		if (isEarthbendable(side) && isTransparent(side.getRelative(face))) {
			flux.add(new Step(side, face));
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
		return "LavaFlux";
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
		return "* JedCore Addon *\n" + config.getString("Abilities.Earth.LavaFlux.Description");
	}

	public double getSpeed() {
		return speed;
	}

	public void setSpeed(double speed) {
		this.speed = speed;
	}

	public int getStepInterval() {
		return stepInterval;
	}

	public void setStepInterval(int stepInterval) {
		this.stepInterval = stepInterval;
	}

	public int getFireTicks() {
		return fireTicks;
	}

	public void setFireTicks(int fireTicks) {
		this.fireTicks = fireTicks;
	}

	public int getRange() {
		return range;
	}

	public void setRange(int range) {
		this.range = range;
	}

	public void setCooldown(long cooldown) {
		this.cooldown = cooldown;
	}

	public long getDuration() {
		return duration;
	}

	public void setDuration(long duration) {
		this.duration = duration;
	}

	public long getCleanup() {
		return cleanup;
	}

	public void setCleanup(long cleanup) {
		this.cleanup = cleanup;
	}

	public double getDamage() {
		return damage;
	}

	public void setDamage(double damage) {
		this.damage = damage;
	}

	public boolean isWave() {
		return wave;
	}

	public void setWave(boolean wave) {
		this.wave = wave;
	}

	public void setLocation(Location location) {
		this.location = location;
	}

	public int getStep() {
		return step;
	}

	public void setStep(int step) {
		this.step = step;
	}

	public int getCounter() {
		return counter;
	}

	public void setCounter(int counter) {
		this.counter = counter;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	public boolean isComplete() {
		return complete;
	}

	public void setComplete(boolean complete) {
		this.complete = complete;
	}

	public List<Location> getFlux() {
		List<Location> locations = new ArrayList<>(flux.size());
		for (Step current : flux) {
			locations.add(current.block.getLocation());
		}
		return locations;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Earth.LavaFlux.Enabled");
	}
}
