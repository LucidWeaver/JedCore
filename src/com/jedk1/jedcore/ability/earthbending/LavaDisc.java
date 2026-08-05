package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.policies.removal.CannotBendRemovalPolicy;
import com.jedk1.jedcore.policies.removal.CompositeRemovalPolicy;
import com.jedk1.jedcore.policies.removal.IsDeadRemovalPolicy;
import com.jedk1.jedcore.policies.removal.IsOfflineRemovalPolicy;
import com.jedk1.jedcore.policies.removal.SwappedSlotsRemovalPolicy;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;

import com.projectkorra.projectkorra.util.TempBlock;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LavaDisc extends LavaAbility implements AddonAbility {

	private static final Particle DUST_PARTICLE = resolveParticle("DUST", "REDSTONE");
	private static final Particle SMOKE_PARTICLE = resolveParticle("SMOKE", "SMOKE_NORMAL");

	private Location location;
	private int recallCount;

	private long time;

	@Attribute(Attribute.DAMAGE)
	private double damage;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute(Attribute.DURATION)
	private long duration;
	private int recallLimit;

	private Block sourceBlock;
	private boolean cooldownApplied;

	private CompositeRemovalPolicy removalPolicy;
	private DiscRenderer discRenderer;
	private State state;

	public LavaDisc(Player player) {
		super(player);

		if (!bPlayer.canBend(this) || !bPlayer.canLavabend()) {
			return;
		}

		if (hasAbility(player, LavaDisc.class)) {
			return;
		}

		state = new HoldState();
		time = System.currentTimeMillis();
		discRenderer = new DiscRenderer(this.player);

		setFields();

		if (prepare()) {
			start();

			if (!isStarted()) {
				RegenTempBlock.revert(sourceBlock);
				sourceBlock = null;
			}
		}
	}

	private static Particle resolveParticle(String... names) {
		for (String name : names) {
			try {
				return Particle.valueOf(name);
			} catch (IllegalArgumentException ignored) {
			}
		}

		return null;
	}

	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);

		damage = config.getDouble("Abilities.Earth.LavaDisc.Damage");
		cooldown = config.getLong("Abilities.Earth.LavaDisc.Cooldown");
		duration = config.getLong("Abilities.Earth.LavaDisc.Duration");
		recallLimit = config.getInt("Abilities.Earth.LavaDisc.RecallLimit") - 1;

		this.removalPolicy = new CompositeRemovalPolicy(this,
				new CannotBendRemovalPolicy(this.bPlayer, this, true, true),
				new IsOfflineRemovalPolicy(this.player),
				new IsDeadRemovalPolicy(this.player),
				new SwappedSlotsRemovalPolicy<>(bPlayer, LavaDisc.class)
		);

		this.removalPolicy.load(config);
	}

	private boolean prepare() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);

		long sourceRegen = config.getLong("Abilities.Earth.LavaDisc.Source.RegenTime");
		boolean lavaOnly = config.getBoolean("Abilities.Earth.LavaDisc.Source.LavaOnly");
		double sourceRange = config.getDouble("Abilities.Earth.LavaDisc.Source.Range");

		Block source = getLavaSourceBlock(sourceRange);

		if (source == null && !lavaOnly) {
			source = getEarthSourceBlock(sourceRange);
		}

		if (source == null) {
			return false;
		}

		sourceBlock = source;
		new RegenTempBlock(source, Material.LAVA, Material.LAVA.createBlockData(bd -> ((Levelled) bd).setLevel(4)), sourceRegen);
		return true;
	}

	@Override
	public void progress() {
		if (this.removalPolicy.shouldRemove()) {
			remove();
			return;
		}

		state.update();
	}

	@Override
	public void remove() {
		if (isStarted() && !isRemoved()) {
			applyCooldown();
		}

		super.remove();
	}

	private void applyCooldown() {
		if (cooldownApplied || bPlayer == null) {
			return;
		}

		cooldownApplied = true;
		bPlayer.addCooldown(this);
	}

	private boolean isLocationSafe() {
		if (!isLocationSafe(location)) {
			return false;
		}

		Block block = location.getBlock();

		return isTransparent(block);
	}

	private boolean isLocationSafe(Location location) {
		if (location == null || location.getWorld() == null) {
			return false;
		}

		return location.getY() >= location.getWorld().getMinHeight() && location.getY() <= (location.getWorld().getMaxHeight() - 1);
	}

	private boolean canDamage(Entity entity) {
		if (!(entity instanceof LivingEntity) || entity instanceof ArmorStand) {
			return false;
		}

		if (entity.getEntityId() == player.getEntityId()) {
			return false;
		}

		if (entity.hasMetadata("BendingImmunity")) {
			return false;
		}

		if (entity instanceof Player && Commands.invincible.contains(entity.getName())) {
			return false;
		}

		return !RegionProtection.isRegionProtected(this, entity.getLocation());
	}

	private void doDamage(Entity entity) {
		DamageHandler.damageEntity(entity, damage, this);

		if (entity.getFireTicks() < 20) {
			entity.setFireTicks(20);
		}

		new FireDamageTimer(entity, player, this);
		entity.getWorld().spawnParticle(Particle.LAVA, entity.getLocation(), 15, Math.random(), Math.random(), Math.random(), 0.1);
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
		return "LavaDisc";
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
		return "* JedCore Addon *\n" + config.getString("Abilities.Earth.LavaDisc.Description");
	}

	public void setLocation(Location location) {
		this.location = location;
	}

	public int getRecallCount() {
		return recallCount;
	}

	public void setRecallCount(int recallCount) {
		this.recallCount = recallCount;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	public double getDamage() {
		return damage;
	}

	public void setDamage(double damage) {
		this.damage = damage;
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

	public int getRecallLimit() {
		return recallLimit;
	}

	public void setRecallLimit(int recallLimit) {
		this.recallLimit = recallLimit;
	}

	public DiscRenderer getDiscRenderer() {
		return discRenderer;
	}

	public void setDiscRenderer(DiscRenderer discRenderer) {
		this.discRenderer = discRenderer;
	}

	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Earth.LavaDisc.Enabled");
	}

	private interface State {
		void update();
	}

	// Renders the particles showing that the player is holding lava.
	// Transitions to ForwardTravelState when the player stops sneaking.
	private class HoldState implements State {
		@Override
		public void update() {
			location = player.getEyeLocation();
			Vector dV = location.getDirection().normalize();
			location.add(new Vector(dV.getX() * 3, dV.getY() * 3, dV.getZ() * 3));

			dV = dV.multiply(0.1);

			while (!isLocationSafe() && isLocationSafe(player.getLocation())) {
				location.subtract(dV);
				if (location.distanceSquared(player.getEyeLocation()) > (3 * 3)) {
					break;
				}
			}

			discRenderer.render(location, false);

			if (!player.isSneaking()) {
				time = System.currentTimeMillis();
				state = new ForwardTravelState();
			}
		}
	}

	private abstract class TravelState implements State {
		private final boolean passHit;

		protected Vector direction;
		protected boolean hasHit;

		public TravelState() {
			this.direction = player.getEyeLocation().getDirection();

			ConfigurationSection config = JedCoreConfig.getConfig(player);

			passHit = config.getBoolean("Abilities.Earth.LavaDisc.ContinueAfterEntityHit");
		}

		protected void move() {
			Set<Entity> damaged = new HashSet<>();

			for (int i = 0; i < 5; i++) {
				location = location.add(direction.clone().multiply(0.15));

				for (Entity entity : GeneralMethods.getEntitiesAroundPoint(location, 2.0D)) {
					if (!canDamage(entity) || !damaged.add(entity)) {
						continue;
					}

					doDamage(entity);

					if (!passHit) {
						hasHit = true;
						return;
					}
				}
			}
		}
	}

	// Moves the disc forward. Makes the disc destroy blocks if enabled.
	// Transitions to ReverseTravelState if the player starts sneaking and can recall.
	// Ends the ability if it times out or hits an entity.
	private class ForwardTravelState extends TravelState {
		@Override
		public void update() {
			if (!isLocationSafe() || System.currentTimeMillis() > time + duration) {
				remove();
				return;
			}

			if (player.isSneaking() && recallCount <= recallLimit) {
				time = System.currentTimeMillis();
				state = new ReverseTravelState();
				return;
			}

			direction = player.getEyeLocation().getDirection().normalize();
			move();
			discRenderer.render(location, true);

			if (hasHit) {
				remove();
			}
		}
	}

	// Returns the disc to the player.
	// Transitions to ForwardTravelState if the player stops sneaking.
	// Transitions to HoldState if the disc gets close enough to the player.
	private class ReverseTravelState extends TravelState {
		@Override
		public void update() {
			if (!isLocationSafe() || System.currentTimeMillis() > time + duration) {
				remove();
				return;
			}

			if (!player.isSneaking()) {
				time = System.currentTimeMillis();
				state = new ForwardTravelState();
				return;
			}

			Location loc = player.getEyeLocation();
			Vector dV = loc.getDirection().normalize();
			loc.add(new Vector(dV.getX() * 3, dV.getY() * 3, dV.getZ() * 3));

			Vector vector = loc.toVector().subtract(location.toVector());
			direction = loc.setDirection(vector).getDirection().normalize();

			move();
			discRenderer.render(location, true);

			if (hasHit) {
				remove();
				return;
			}

			double distanceAway = location.distance(loc);
			if (distanceAway < 0.5) {
				recallCount++;
				// Player is holding the disc when it gets close enough to them.
				state = new HoldState();
			}
		}
	}

	private class DiscRenderer {
		private final Player player;
		private int angle;

		private final boolean damageBlocks;
		private final List<String> meltable;
		private final long regenTime;
		private final boolean lavaTrail;

		private final int particles;


		public DiscRenderer(Player player) {
			this.player = player;
			this.angle = 0;

			ConfigurationSection config = JedCoreConfig.getConfig(this.player);

			damageBlocks = config.getBoolean("Abilities.Earth.LavaDisc.Destroy.BlockDamage");
			meltable = config.getStringList("Abilities.Earth.LavaDisc.Destroy.AdditionalMeltableBlocks");
			regenTime = config.getLong("Abilities.Earth.LavaDisc.Destroy.RegenTime");
			lavaTrail = config.getBoolean("Abilities.Earth.LavaDisc.Destroy.LavaTrail");
			particles = config.getInt("Abilities.Earth.LavaDisc.Particles");
		}

		void render(Location location, boolean largeLava) {
			if (largeLava)
				location.getWorld().spawnParticle(Particle.LAVA, location, particles * 2, Math.random(), Math.random(), Math.random(), 0.1);
			else
				location.getWorld().spawnParticle(Particle.LAVA, location, 1, Math.random(), Math.random(), Math.random(), 0.1);
			angle += 1;
			if (angle >= 360) angle -= 360;
			double startAngle = Math.toRadians(angle);
			for (Location l : JCMethods.getCirclePoints(location, 20, 1, startAngle)) {
				if (DUST_PARTICLE != null)
					location.getWorld().spawnParticle(DUST_PARTICLE, l, 0, 196 / 255.0, 93 / 255.0, 0, 0.005F, new Particle.DustOptions(Color.fromRGB(196, 93, 0), 1));
				if (largeLava && damageBlocks)
					damageBlocks(l);
			}
			for (Location l : JCMethods.getCirclePoints(location, 10, 0.5, startAngle)) {
				location.getWorld().spawnParticle(Particle.FLAME, l, 1, 0, 0, 0, 0.01);
				if (SMOKE_PARTICLE != null)
					location.getWorld().spawnParticle(SMOKE_PARTICLE, l, 1, 0, 0, 0, 0.05);
				if (largeLava && damageBlocks)
					damageBlocks(l);
			}
		}

		private void damageBlocks(Location l) {
			Block block = l.getBlock();
			if (!RegionProtection.isRegionProtected(player, l, LavaDisc.this)) {
				if (!TempBlock.isTempBlock(block) && (isEarthbendable(player, block) || isMetal(block) || meltable.contains(block.getType().name()))) {
					if (DensityShift.isPassiveSand(block)) {
						DensityShift.revertSand(block);
					}
					if (lavaTrail) {
						new RegenTempBlock(block, Material.LAVA, Material.LAVA.createBlockData(bd -> ((Levelled) bd).setLevel(4)), regenTime);
					} else {
						new RegenTempBlock(block, Material.AIR, Material.AIR.createBlockData(), regenTime);
					}
					l.getWorld().spawnParticle(Particle.LAVA, l, particles * 2, Math.random(), Math.random(), Math.random(), 0.2);
				}
			}
		}
	}
}
