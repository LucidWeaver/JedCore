package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.collision.AABB;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.collision.Ray;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.BlockUtil;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.AttributeCache;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.event.AbilityRecalculateAttributeEvent;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;

import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class EarthShard extends EarthAbility implements AddonAbility {
	private static final String METAL_DAMAGE_ATTRIBUTE = "Metal" + Attribute.DAMAGE;
	private static final long PROJECTILE_TIMEOUT = 20000;
	private static final double BLOCK_COLLISION_EPSILON = 1.0E-4;

	@Attribute(Attribute.SELECT_RANGE)
	private int range;
	@Attribute(Attribute.RANGE)
	private int abilityRange;
	@Attribute(Attribute.SPEED)
	private double speed;
	@Attribute(Attribute.DAMAGE)
	private double normalDmg;
	@Attribute(METAL_DAMAGE_ATTRIBUTE)
	private double metalDmg;
	@Attribute("MaxShots")
	private int maxShards;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;

	private boolean isThrown = false;
	private boolean cooldownApplied;
	private long thrownTime;
	private Location origin;
	private double abilityCollisionRadius;
	private double entityCollisionRadius;

	private final List<TempBlock> tblockTracker = new ArrayList<>();
	private final List<TempBlock> readyBlocksTracker = new ArrayList<>();
	private final Map<TempFallingBlock, TempBlock> preparingBlocks = new LinkedHashMap<>();
	private final Map<TempBlock, TempBlock> readySources = new LinkedHashMap<>();
	private final Map<TempBlock, BlockData> sourceBlockData = new LinkedHashMap<>();
	private final List<TempFallingBlock> fallingBlocks = new ArrayList<>();
	private final List<ShardFlight> shardFlights = new ArrayList<>();

	private boolean allowKnockup;
	private double knockupVelocity;
	private double knockupRange;

	private boolean allowKnockupSelf;
	private double knockupSelfVelocity;
	private double knockupSelfRange;

	private static class ShardFlight {
		private final List<TempFallingBlock> blocks;
		private final Map<TempFallingBlock, Vector> offsets;
		private final Map<TempFallingBlock, BlockData> impactData;
		private final Map<TempFallingBlock, Location> previousLocations;
		private final Location origin;
		private final Vector direction;
		private final int normalShards;
		private final int metalShards;
		private boolean falling;

		private ShardFlight(List<TempFallingBlock> blocks, Map<TempFallingBlock, Vector> offsets, Map<TempFallingBlock, BlockData> impactData, Location origin, Vector direction, int normalShards, int metalShards) {
			this.blocks = blocks;
			this.offsets = offsets;
			this.impactData = impactData;
			this.previousLocations = new LinkedHashMap<>();
			for (TempFallingBlock block : blocks) {
				this.previousLocations.put(block, block.getLocation().clone());
			}
			this.origin = origin;
			this.direction = direction;
			this.normalShards = normalShards;
			this.metalShards = metalShards;
		}
	}

	public EarthShard(Player player) {
		super(player);
		setFields();

		if (!bPlayer.canBend(this)) {
			return;
		}

		if (hasAbility(player, EarthShard.class)) {
			for (EarthShard es : EarthShard.getAbilities(player, EarthShard.class)) {
				if (es.isThrown) {
					if (System.currentTimeMillis() - es.thrownTime >= PROJECTILE_TIMEOUT) {
						es.remove();
						continue;
					}
					return;
				}

				es.select();
				return;
			}
		}

		origin = player.getLocation().clone();
		start();
		if (!isStarted()) {
			return;
		}

		raiseEarthBlock(getEarthSourceBlock(range));
		if (tblockTracker.isEmpty()) {
			remove();
		}
	}

	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);

		range = config.getInt("Abilities.Earth.EarthShard.PrepareRange");
		abilityRange = config.getInt("Abilities.Earth.EarthShard.AbilityRange");
		speed = config.getDouble("Abilities.Earth.EarthShard.Speed");
		normalDmg = config.getDouble("Abilities.Earth.EarthShard.Damage.Normal");
		metalDmg = config.getDouble("Abilities.Earth.EarthShard.Damage.Metal");
		maxShards = config.getInt("Abilities.Earth.EarthShard.MaxShards");
		cooldown = config.getLong("Abilities.Earth.EarthShard.Cooldown");
		abilityCollisionRadius = config.getDouble("Abilities.Earth.EarthShard.AbilityCollisionRadius");
		entityCollisionRadius = config.getDouble("Abilities.Earth.EarthShard.EntityCollisionRadius");
		allowKnockup = config.getBoolean("Abilities.Earth.EarthShard.KnockUp.Others.Allow");
		knockupVelocity = config.getDouble("Abilities.Earth.EarthShard.KnockUp.Others.Velocity");
		knockupRange = config.getDouble("Abilities.Earth.EarthShard.KnockUp.Others.Range");
		allowKnockupSelf = config.getBoolean("Abilities.Earth.EarthShard.KnockUp.Self.Allow");
		knockupSelfVelocity = config.getDouble("Abilities.Earth.EarthShard.KnockUp.Self.Velocity");
		knockupSelfRange = config.getDouble("Abilities.Earth.EarthShard.KnockUp.Self.Range");
	}

	public static void applyAvatarStateModifier(AbilityRecalculateAttributeEvent event) {
		EarthShard ability = (EarthShard) event.getAbility();

		if (ability.bPlayer == null || !ability.bPlayer.isAvatarState() || !event.getAttribute().equals(METAL_DAMAGE_ATTRIBUTE)) {
			return;
		}

		Map<String, AttributeCache> attributes = CoreAbility.getAttributeCache(ability);
		AttributeCache target = attributes.get(METAL_DAMAGE_ATTRIBUTE);
		AttributeCache inherited = attributes.get(Attribute.DAMAGE);

		if (target == null || inherited == null || target.getAvatarStateModifier().isPresent()) {
			return;
		}

		if (inherited.getAvatarStateModifier().isPresent()) {
			event.addModification(inherited.getAvatarStateModifier().get());
		}
	}

	public void select() {
		raiseEarthBlock(getEarthSourceBlock(range));
	}

	public void raiseEarthBlock(Block block) {
		if (block == null) return;
		if (tblockTracker.size() >= maxShards) return;
		if (block.getY() >= origin.getBlockY() + 2) return;
		if (RegionProtection.isRegionProtected(this, block.getLocation())) return;

		Vector blockVector = block.getLocation().toVector().toBlockVector().setY(0);

		for (TempBlock tempBlock : tblockTracker) {
			if (tempBlock.getLocation().getWorld() != block.getWorld()) continue;

			Vector tempBlockVector = tempBlock.getLocation().toVector().toBlockVector().setY(0);
			if (tempBlockVector.equals(blockVector)) return;
		}

		for (int i = 1; i < 4; i++) {
			if (!isTransparent(block.getRelative(BlockFace.UP, i))) return;
		}

		if (!isEarthbendable(block)) return;

		if (isMetal(block)) {
			playMetalbendingSound(block.getLocation());
		} else {
			block.getLocation().add(0, 1, 0).getWorld().spawnParticle(
					Particle.BLOCK_CRACK,
					block.getLocation().add(0, 1, 0),
					20,
					0.0, 0.0, 0.0,
					0.0,
					block.getBlockData()
			);
			playEarthbendingSound(block.getLocation());
		}

		Material material = getCorrectType(block);
		BlockData sourceData = material.createBlockData();
		BlockData displayData = isLava(material) ? Material.MAGMA_BLOCK.createBlockData() : sourceData;

		if (DensityShift.isPassiveSand(block)) {
			DensityShift.revertSand(block);
		}

		Location loc = block.getLocation().add(0.5, 0, 0.5);
		TempFallingBlock preparingBlock = new TempFallingBlock(loc, displayData, new Vector(0, 0.8, 0), this, true);
		TempBlock tb = JCMethods.createTempBlock(block, Material.AIR.createBlockData());
		tblockTracker.add(tb);
		preparingBlocks.put(preparingBlock, tb);
		sourceBlockData.put(tb, sourceData);

		handleKnockup(block);
	}

	private void handleKnockup(Block origin) {
		if (!allowKnockup && !allowKnockupSelf) return;

		Location originLoc = origin.getLocation().add(0.5, 0.5, 0.5);
		World world = origin.getWorld();
		double queryRange = Math.max(knockupRange, knockupSelfRange);

		for (Entity entity : world.getNearbyEntities(originLoc, queryRange, queryRange, queryRange)) {
			if (entity instanceof FallingBlock) continue;

			if (entity.equals(player)) {
				if (!allowKnockupSelf) continue;
				if (entity.getLocation().distance(originLoc) <= knockupSelfRange) {
					GeneralMethods.setVelocity(this, entity, entity.getVelocity().add(new Vector(0, knockupSelfVelocity, 0)));
				}
			} else {
				if (!allowKnockup) continue;
				if (isProtected(entity)) continue;
				if (entity.getLocation().distance(originLoc) <= knockupRange) {
					GeneralMethods.setVelocity(this, entity, entity.getVelocity().add(new Vector(0, knockupVelocity, 0)));
				}
			}
		}
	}

	private boolean isProtected(Entity entity) {
		return RegionProtection.isRegionProtected(this, entity.getLocation())
				|| entity instanceof Player && Commands.invincible.contains(entity.getName());
	}

	public Material getCorrectType(Block block) {
		if (block.getType() == Material.SAND) {
			return Material.SANDSTONE;
		}
		if (block.getType() == Material.RED_SAND) {
			return Material.RED_SANDSTONE;
		}
		if (block.getType() == Material.GRAVEL) {
			return Material.COBBLESTONE;
		}
		if (block.getType().name().endsWith("CONCRETE_POWDER")) {
			return Material.getMaterial(block.getType().name().replace("_POWDER", ""));
		}

		return block.getType();
	}

	public void progress() {
		if (player == null || !player.isOnline() || player.isDead()) {
			remove();
			return;
		}

		if (!preparingBlocks.isEmpty() || !readyBlocksTracker.isEmpty()) {
			if (!bPlayer.canBendIgnoreCooldowns(this)) {
				remove();
				return;
			}
		}

		progressPreparingBlocks();
		progressShardFlights();

		if (preparingBlocks.isEmpty() && readyBlocksTracker.isEmpty() && shardFlights.isEmpty()) {
			remove();
		}
	}

	private void progressPreparingBlocks() {
		List<TempFallingBlock> activeBlocks = TempFallingBlock.getFromAbility(this);
		for (Map.Entry<TempFallingBlock, TempBlock> entry : new ArrayList<>(preparingBlocks.entrySet())) {
			TempFallingBlock tfb = entry.getKey();
			FallingBlock fb = tfb.getFallingBlock();

			if (!activeBlocks.contains(tfb) || fb.isDead() || !fb.isValid()) {
				discardPreparingBlock(tfb, entry.getValue());
				continue;
			}

			if (fb.getLocation().getY() >= origin.getBlockY() + 2) {
				Block readyBlock = fb.getLocation().getBlock();
				if (!isTransparent(readyBlock) || RegionProtection.isRegionProtected(this, readyBlock.getLocation())) {
					discardPreparingBlock(tfb, entry.getValue());
					continue;
				}

				TempBlock preparedBlock = JCMethods.createTempBlock(readyBlock, fb.getBlockData());
				readyBlocksTracker.add(preparedBlock);
				readySources.put(preparedBlock, entry.getValue());
				preparingBlocks.remove(tfb);
				tfb.remove();
			} else if (fb.getVelocity().getY() <= 0) {
				discardPreparingBlock(tfb, entry.getValue());
			}
		}
	}

	private void progressShardFlights() {
		List<TempFallingBlock> activeBlocks = TempFallingBlock.getFromAbility(this);
		for (ShardFlight flight : new ArrayList<>(shardFlights)) {
			if (!isFlightIntact(flight, activeBlocks)) {
				removeFlight(flight);
				continue;
			}

			Location center = getFlightCenter(flight);
			if (center == null) {
				removeFlight(flight);
				continue;
			}
			if (hasBlockCollision(flight)) {
				impactFlight(flight);
				continue;
			}
			alignFlight(flight, center);

			if (!flight.falling) {
				double rangeSquared = (double) abilityRange * abilityRange;
				if (speed <= 0 || abilityRange <= 0 || center.distanceSquared(flight.origin) >= rangeSquared) {
					flight.falling = true;
					for (TempFallingBlock tfb : flight.blocks) {
						tfb.getFallingBlock().setGravity(true);
					}
				} else {
					Vector velocity = flight.direction.clone().multiply(speed);
					for (TempFallingBlock tfb : flight.blocks) {
						FallingBlock fb = tfb.getFallingBlock();
						fb.setGravity(false);
						fb.setVelocity(velocity.clone());
					}
				}
			} else {
				Vector velocity = flight.blocks.get(0).getFallingBlock().getVelocity();
				for (TempFallingBlock tfb : flight.blocks) {
					FallingBlock fb = tfb.getFallingBlock();
					fb.setGravity(true);
					fb.setVelocity(velocity.clone());
				}
			}

			boolean[] consumed = {false};
			for (TempFallingBlock tfb : new ArrayList<>(flight.blocks)) {
				FallingBlock fb = tfb.getFallingBlock();
				AABB collider = BlockUtil.getFallingBlockBoundsFull(fb).scale(entityCollisionRadius * 2.0);

				CollisionDetector.checkEntityCollisions(player, fb.getWorld(), collider, entity -> {
					if (isProtected(entity)) {
						return false;
					}

					double damage = flight.normalShards * normalDmg + flight.metalShards * metalDmg;
					LivingEntity target = (LivingEntity) entity;
					target.setNoDamageTicks(0);
					DamageHandler.damageEntity(target, damage, this);
					target.setNoDamageTicks(0);
					displayFlightImpact(flight);
					removeFlight(flight);
					consumed[0] = true;
					return true;
				});

				if (consumed[0]) {
					break;
				}
			}

			if (consumed[0]) {
				continue;
			}
			rememberFlightLocations(flight);
		}
	}

	private boolean hasBlockCollision(ShardFlight flight) {
		for (TempFallingBlock tfb : flight.blocks) {
			Location previousLocation = flight.previousLocations.get(tfb);
			if (previousLocation != null && hasBlockCollision(tfb.getFallingBlock(), previousLocation)) {
				return true;
			}
		}
		return false;
	}

	private boolean hasBlockCollision(FallingBlock fallingBlock, Location previousLocation) {
		Location currentLocation = fallingBlock.getLocation();
		if (previousLocation.getWorld() != currentLocation.getWorld()) {
			return true;
		}

		AABB previousBounds = AABB.BlockBounds.at(previousLocation.clone().subtract(0.5, 0, 0.5));
		AABB currentBounds = BlockUtil.getFallingBlockBoundsFull(fallingBlock);
		AABB contactBounds = currentBounds.grow(BLOCK_COLLISION_EPSILON, BLOCK_COLLISION_EPSILON, BLOCK_COLLISION_EPSILON);
		Vector previousCenter = previousBounds.mid();
		Vector movement = currentBounds.mid().subtract(previousCenter);
		double distance = movement.length();
		Ray ray = distance > BLOCK_COLLISION_EPSILON ? new Ray(previousCenter, movement.clone().normalize()) : null;
		Vector halfExtents = currentBounds.getHalfExtents();

		int minX = (int) Math.floor(Math.min(previousBounds.min().getX(), currentBounds.min().getX()) - BLOCK_COLLISION_EPSILON);
		int maxX = (int) Math.floor(Math.max(previousBounds.max().getX(), currentBounds.max().getX()) + BLOCK_COLLISION_EPSILON);
		int minZ = (int) Math.floor(Math.min(previousBounds.min().getZ(), currentBounds.min().getZ()) - BLOCK_COLLISION_EPSILON);
		int maxZ = (int) Math.floor(Math.max(previousBounds.max().getZ(), currentBounds.max().getZ()) + BLOCK_COLLISION_EPSILON);
		World world = currentLocation.getWorld();
		int minY = Math.max(world.getMinHeight(), (int) Math.floor(Math.min(previousBounds.min().getY(), currentBounds.min().getY()) - BLOCK_COLLISION_EPSILON));
		int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(Math.max(previousBounds.max().getY(), currentBounds.max().getY()) + BLOCK_COLLISION_EPSILON));

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					Block block = world.getBlockAt(x, y, z);
					if (isReadyShardBlock(block)) {
						continue;
					}
					AABB blockBounds = new AABB(block).at(block.getLocation());
					if (!blockBounds.hasVolume()) {
						continue;
					}
					if (contactBounds.intersects(blockBounds)) {
						return true;
					}
					if (ray == null) {
						continue;
					}

					AABB expandedBlock = blockBounds.grow(halfExtents.getX(), halfExtents.getY(), halfExtents.getZ());
					Optional<Double> hit = expandedBlock.intersects(ray);
					if (hit.isPresent() && hit.get() >= -BLOCK_COLLISION_EPSILON && hit.get() <= distance + BLOCK_COLLISION_EPSILON) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private boolean isReadyShardBlock(Block block) {
		for (TempBlock readyBlock : readyBlocksTracker) {
			Block candidate = readyBlock.getBlock();
			if (candidate.getWorld().equals(block.getWorld())
					&& candidate.getX() == block.getX()
					&& candidate.getY() == block.getY()
					&& candidate.getZ() == block.getZ()) {
				return true;
			}
		}
		return false;
	}

	private void rememberFlightLocations(ShardFlight flight) {
		for (TempFallingBlock tfb : flight.blocks) {
			flight.previousLocations.put(tfb, tfb.getLocation().clone());
		}
	}

	private boolean isFlightIntact(ShardFlight flight, List<TempFallingBlock> activeBlocks) {
		if (flight.blocks.isEmpty()) {
			return false;
		}

		for (TempFallingBlock tfb : flight.blocks) {
			FallingBlock fb = tfb.getFallingBlock();
			if (!activeBlocks.contains(tfb) || fb.isDead() || !fb.isValid()) {
				return false;
			}
		}
		return true;
	}

	private Location getFlightCenter(ShardFlight flight) {
		if (flight.blocks.isEmpty()) {
			return null;
		}

		Location center = flight.blocks.get(0).getLocation().clone().multiply(0);
		for (TempFallingBlock tfb : flight.blocks) {
			center.add(tfb.getLocation().toVector());
		}
		return center.multiply(1.0 / flight.blocks.size());
	}

	private void alignFlight(ShardFlight flight, Location center) {
		for (TempFallingBlock tfb : flight.blocks) {
			FallingBlock fb = tfb.getFallingBlock();
			Location expected = center.clone().add(flight.offsets.get(tfb));
			if (fb.getLocation().distanceSquared(expected) > 0.0001) {
				fb.teleport(expected);
			}
		}
	}

	private void displayFlightImpact(ShardFlight flight) {
		for (TempFallingBlock tfb : flight.blocks) {
			FallingBlock fb = tfb.getFallingBlock();
			BlockData particleData = flight.impactData.getOrDefault(tfb, fb.getBlockData());
			fb.getWorld().spawnParticle(Particle.BLOCK_CRACK, fb.getLocation(), 20, 0, 0, 0, 0, particleData);
		}
	}

	private void impactFlight(ShardFlight flight) {
		if (!shardFlights.contains(flight)) {
			return;
		}
		displayFlightImpact(flight);
		removeFlight(flight);
	}

	private void removeFlight(ShardFlight flight) {
		for (TempFallingBlock tfb : flight.blocks) {
			tfb.remove();
		}
		fallingBlocks.removeAll(flight.blocks);
		shardFlights.remove(flight);
	}

	private void discardPreparingBlock(TempFallingBlock tfb, TempBlock source) {
		tfb.remove();
		source.revertBlock();
		preparingBlocks.remove(tfb);
		tblockTracker.remove(source);
		sourceBlockData.remove(source);
	}

	private List<TempFallingBlock> getActiveProjectiles() {
		List<TempFallingBlock> activeBlocks = TempFallingBlock.getFromAbility(this);
		for (ShardFlight flight : new ArrayList<>(shardFlights)) {
			if (!isFlightIntact(flight, activeBlocks)) {
				removeFlight(flight);
			}
		}
		fallingBlocks.retainAll(activeBlocks);
		return new ArrayList<>(fallingBlocks);
	}

	public static void throwShard(Player player) {
		if (hasAbility(player, EarthShard.class)) {
			for (EarthShard es : EarthShard.getAbilities(player, EarthShard.class)) {
				if (es.canThrowShard()) {
					es.throwShard();
					return;
				}
			}
		}
	}

	private boolean canThrowShard() {
		return preparingBlocks.isEmpty() && !readyBlocksTracker.isEmpty();
	}

	public void throwShard() {
		if (!canThrowShard()) {
			return;
		}

		Vector lookDirection = player.getEyeLocation().getDirection().normalize();
		List<Entity> avoid = new ArrayList<>();
		for (TempFallingBlock tfb : getActiveProjectiles()) {
			avoid.add(tfb.getFallingBlock());
		}
		Entity targetedEntity = GeneralMethods.getTargetedEntity(player, abilityRange, avoid);
		Location targetLocation = player.getEyeLocation().clone().add(lookDirection.clone().multiply(abilityRange));

		if (targetedEntity != null) {
			targetLocation = targetedEntity.getLocation().clone().add(0, targetedEntity.getHeight() / 2.0, 0);
		}

		List<TempBlock> group = getNearestReadyGroup(targetLocation);
		if (group.isEmpty()) {
			return;
		}

		Location groupCenter = getReadyGroupCenter(group);
		Vector direction = targetedEntity == null
				? lookDirection
				: targetLocation.toVector().subtract(groupCenter.toVector());
		if (direction.lengthSquared() == 0 || Double.isNaN(direction.length())) {
			direction = lookDirection;
		}
		direction.normalize();

		launchShardGroup(group, groupCenter, direction);
	}

	private List<TempBlock> getNearestReadyGroup(Location targetLocation) {
		List<TempBlock> nearest = new ArrayList<>();
		double nearestDistance = Double.MAX_VALUE;
		for (List<TempBlock> group : getReadyGroups()) {
			double distance = Double.MAX_VALUE;
			for (TempBlock tb : group) {
				distance = Math.min(distance, getShardLocation(tb).distanceSquared(targetLocation));
			}
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearest = group;
			}
		}
		return nearest;
	}

	private List<List<TempBlock>> getReadyGroups() {
		List<List<TempBlock>> groups = new ArrayList<>();
		Set<TempBlock> remaining = new HashSet<>(readyBlocksTracker);

		while (!remaining.isEmpty()) {
			TempBlock seed = null;
			for (TempBlock tb : readyBlocksTracker) {
				if (remaining.contains(tb)) {
					seed = tb;
					break;
				}
			}

			List<TempBlock> group = new ArrayList<>();
			ArrayDeque<TempBlock> queue = new ArrayDeque<>();
			queue.add(seed);
			remaining.remove(seed);

			while (!queue.isEmpty()) {
				TempBlock current = queue.removeFirst();
				group.add(current);
				for (TempBlock candidate : new ArrayList<>(remaining)) {
					if (sharesFace(current, candidate)) {
						remaining.remove(candidate);
						queue.addLast(candidate);
					}
				}
			}
			groups.add(group);
		}
		return groups;
	}

	private boolean sharesFace(TempBlock first, TempBlock second) {
		Block a = first.getBlock();
		Block b = second.getBlock();
		if (!a.getWorld().equals(b.getWorld())) {
			return false;
		}
		return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ()) == 1;
	}

	private Location getShardLocation(TempBlock tb) {
		return tb.getLocation().clone().add(0.5, 0, 0.5);
	}

	private Location getReadyGroupCenter(List<TempBlock> group) {
		Location center = getShardLocation(group.get(0)).multiply(0);
		for (TempBlock tb : group) {
			center.add(getShardLocation(tb).toVector());
		}
		return center.multiply(1.0 / group.size());
	}

	private void launchShardGroup(List<TempBlock> group, Location groupCenter, Vector direction) {
		List<TempFallingBlock> launchedBlocks = new ArrayList<>();
		Map<TempFallingBlock, Vector> offsets = new LinkedHashMap<>();
		Map<TempFallingBlock, BlockData> impactData = new LinkedHashMap<>();
		Vector velocity = direction.clone().multiply(speed);
		int normalShards = 0;
		int metalShards = 0;

		for (TempBlock tb : group) {
			Location spawnLocation = getShardLocation(tb);
			TempBlock source = readySources.get(tb);
			BlockData particleData = source == null ? null : sourceBlockData.get(source);
			if (particleData == null) {
				particleData = tb.getBlock().getBlockData();
			}
			Material material = particleData.getMaterial();
			TempFallingBlock tfb = new TempFallingBlock(spawnLocation, tb.getBlock().getBlockData(), velocity.clone(), this);
			tfb.getFallingBlock().setGravity(false);
			launchedBlocks.add(tfb);
			offsets.put(tfb, spawnLocation.toVector().subtract(groupCenter.toVector()));
			impactData.put(tfb, particleData);
			fallingBlocks.add(tfb);
			if (isMetal(material)) {
				metalShards++;
			} else {
				normalShards++;
			}
		}

		ShardFlight flight = new ShardFlight(launchedBlocks, offsets, impactData, groupCenter.clone(), direction.clone(), normalShards, metalShards);
		shardFlights.add(flight);
		for (TempFallingBlock tfb : launchedBlocks) {
			tfb.setOnPlace(ignored -> impactFlight(flight));
		}
		for (TempBlock tb : group) {
			releaseReadyBlock(tb);
		}

		if (!isThrown) {
			isThrown = true;
			thrownTime = System.currentTimeMillis();
		}
		if (readyBlocksTracker.isEmpty()) {
			applyCooldown();
		}
	}

	private void releaseReadyBlock(TempBlock readyBlock) {
		TempBlock source = readySources.remove(readyBlock);
		readyBlock.revertBlock();
		readyBlocksTracker.remove(readyBlock);
		if (source != null) {
			source.revertBlock();
			tblockTracker.remove(source);
			sourceBlockData.remove(source);
		}
	}

	private void applyCooldown() {
		if (!isThrown || cooldownApplied || bPlayer == null) {
			return;
		}
		cooldownApplied = true;
		bPlayer.addCooldown(this);
	}

	public void revertBlocks() {
		for (TempBlock tb : tblockTracker) {
			tb.revertBlock();
		}

		for (TempBlock tb : readyBlocksTracker) {
			tb.revertBlock();
		}

		tblockTracker.clear();
		readyBlocksTracker.clear();
		readySources.clear();
		sourceBlockData.clear();
	}

	@Override
	public void remove() {
		applyCooldown();
		for (TempFallingBlock tfb : TempFallingBlock.getFromAbility(this)) {
			tfb.remove();
		}

		preparingBlocks.clear();
		fallingBlocks.clear();
		shardFlights.clear();
		revertBlocks();

		super.remove();
	}

	@Override
	public long getCooldown() {
		return cooldown;
	}

	@Override
	public Location getLocation() {
		return null;
	}

	@Override
	public List<Location> getLocations() {
		List<Location> locations = new ArrayList<>();
		for (TempFallingBlock tfb : getActiveProjectiles()) {
			locations.add(tfb.getLocation());
		}
		return locations;
	}

	@Override
	public void handleCollision(Collision collision) {
		getActiveProjectiles();
		if (!collision.isRemovingFirst()) {
			return;
		}

		Location location = collision.getLocationSecond();
		double firstRadius = collision.getAbilityFirst().getCollisionRadius();
		double secondRadius = collision.getAbilitySecond().getCollisionRadius();
		double collisionRadiusSq = (firstRadius + secondRadius) * (firstRadius + secondRadius);

		for (ShardFlight flight : new ArrayList<>(shardFlights)) {
			for (TempFallingBlock tfb : flight.blocks) {
				Location blockLocation = tfb.getLocation();
				if (blockLocation.getWorld().equals(location.getWorld()) && blockLocation.distanceSquared(location) <= collisionRadiusSq) {
					removeFlight(flight);
					break;
				}
			}
		}
	}

	@Override
	public double getCollisionRadius() {
		return abilityCollisionRadius;
	}

	@Override
	public String getName() {
		return "EarthShard";
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
		return "* JedCore Addon *\n" + config.getString("Abilities.Earth.EarthShard.Description");
	}

	public int getRange() {
		return range;
	}

	public void setRange(int range) {
		this.range = range;
	}

	public int getAbilityRange() {
		return abilityRange;
	}

	public void setAbilityRange(int abilityRange) {
		this.abilityRange = abilityRange;
	}

	public double getSpeed() {
		return speed;
	}

	public void setSpeed(double speed) {
		this.speed = speed;
	}

	public double getNormalDmg() {
		return normalDmg;
	}

	public void setNormalDmg(double normalDmg) {
		this.normalDmg = normalDmg;
	}

	public double getMetalDmg() {
		return metalDmg;
	}

	public void setMetalDmg(double metalDmg) {
		this.metalDmg = metalDmg;
	}

	public int getMaxShards() {
		return maxShards;
	}

	public void setMaxShards(int maxShards) {
		this.maxShards = maxShards;
	}

	public void setCooldown(long cooldown) {
		this.cooldown = cooldown;
	}

	public boolean isThrown() {
		return isThrown;
	}

	public void setThrown(boolean thrown) {
		isThrown = thrown;
		thrownTime = thrown ? System.currentTimeMillis() : 0;
	}

	public long getThrownTime() {
		return thrownTime;
	}

	public Location getOrigin() {
		return origin;
	}

	public void setOrigin(Location origin) {
		this.origin = origin;
	}

	public double getAbilityCollisionRadius() {
		return abilityCollisionRadius;
	}

	public void setAbilityCollisionRadius(double abilityCollisionRadius) {
		this.abilityCollisionRadius = abilityCollisionRadius;
	}

	public double getEntityCollisionRadius() {
		return entityCollisionRadius;
	}

	public void setEntityCollisionRadius(double entityCollisionRadius) {
		this.entityCollisionRadius = entityCollisionRadius;
	}

	public List<TempBlock> getTblockTracker() {
		return tblockTracker;
	}

	public List<TempBlock> getReadyBlocksTracker() {
		return readyBlocksTracker;
	}

	public List<TempFallingBlock> getFallingBlocks() {
		return fallingBlocks;
	}

	public boolean isAllowKnockup() {
		return allowKnockup;
	}

	public void setAllowKnockup(boolean allowKnockup) {
		this.allowKnockup = allowKnockup;
	}

	public double getKnockupVelocity() {
		return knockupVelocity;
	}

	public void setKnockupVelocity(double knockupVelocity) {
		this.knockupVelocity = knockupVelocity;
	}

	public double getKnockupRange() {
		return knockupRange;
	}

	public void setKnockupRange(double knockupRange) {
		this.knockupRange = knockupRange;
	}

	public boolean isAllowKnockupSelf() {
		return allowKnockupSelf;
	}

	public void setAllowKnockupSelf(boolean allowKnockupSelf) {
		this.allowKnockupSelf = allowKnockupSelf;
	}

	public double getKnockupSelfVelocity() {
		return knockupSelfVelocity;
	}

	public void setKnockupSelfVelocity(double knockupSelfVelocity) {
		this.knockupSelfVelocity = knockupSelfVelocity;
	}

	public double getKnockupSelfRange() {
		return knockupSelfRange;
	}

	public void setKnockupSelfRange(double knockupSelfRange) {
		this.knockupSelfRange = knockupSelfRange;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Earth.EarthShard.Enabled");
	}
}
