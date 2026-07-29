package com.jedk1.jedcore.ability.waterbending.combo;

import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.ability.waterbending.WaterBlast;
import com.jedk1.jedcore.collision.AABB;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.AttributeCache;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.event.AbilityRecalculateAttributeEvent;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.Torrent;
import com.projectkorra.projectkorra.waterbending.WaterManipulation;
import com.projectkorra.projectkorra.waterbending.ice.PhaseChange;
import com.projectkorra.projectkorra.waterbending.plant.PlantRegrowth;
import com.projectkorra.projectkorra.waterbending.util.WaterReturn;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class WaterGimbal extends WaterAbility implements AddonAbility, ComboAbility {

	@Attribute(Attribute.SELECT_RANGE)
	private int sourceRange;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute("Width")
	private double ringSize;
	@Attribute(Attribute.RANGE)
	private double range;
	@Attribute(Attribute.DAMAGE)
	private double damage;
	@Attribute(Attribute.SPEED)
	private double speed;
	private int animSpeed;
	private boolean plantSourcing;
	private boolean snowSourcing;
	private boolean requireAdjacentPlants;
	private boolean requireAdjacentSources;
	private boolean canUseBottle;
	private double abilityCollisionRadius;
	private double entityCollisionRadius;
	
	private int step;
	private boolean initializing;
	private boolean leftVisible = true;
	private boolean rightVisible = true;
	private boolean rightConsumed = false;
	private boolean leftConsumed = false;
	private Block sourceBlock;
	private TempBlock source;
	private Location sourceLoc;
	private Location origin1;
	private Location origin2;
	private boolean usingBottle;

	private final Random rand = new Random();

	private static final String SPIRAL_PATH = "Abilities.Water.WaterCombo.WaterGimbal.SpiralRelease.";
	private static final String SPIRAL_RANGE_ATTRIBUTE = "Spiral" + Attribute.RANGE;
	private static final String SPIRAL_DAMAGE_ATTRIBUTE = "Spiral" + Attribute.DAMAGE;
	private static final String SPIRAL_SPEED_ATTRIBUTE = "Spiral" + Attribute.SPEED;
	private static final String SPIRAL_PUSH_DISTANCE_ATTRIBUTE = "SpiralPushDistance";
	private static final int MAX_SAMPLE_LAYERS_PER_TICK = 32;
	private static final double SOUND_SPACING = 7.0;
	private static final double MIN_HIT_DISTANCE = 3.0;
	private static final int START_CLEARANCE = 4;
	private static final double NOT_PUSHING = -1.0;

	private static BlockData sourceWater;

	private boolean firing;
	private Vector spiralCenter;
	private Frame spiralFrame;
	private Plan spiralPlan;
	private Location spiralLocation;
	@Attribute(SPIRAL_RANGE_ATTRIBUTE)
	private double spiralRange;
	@Attribute(SPIRAL_DAMAGE_ATTRIBUTE)
	private double spiralDamage;
	@Attribute(SPIRAL_SPEED_ATTRIBUTE)
	private double spiralSpeed;
	private long trailRevertTime;
	private double sampleSpacing;
	private boolean pushEnabled;
	@Attribute(Attribute.KNOCKBACK)
	private double pushStrength;
	private double pushLift;
	@Attribute(SPIRAL_PUSH_DISTANCE_ATTRIBUTE)
	private double pushDistance;
	private double axialDistance;
	private double nextSoundDistance;
	private double pushEndDistance = NOT_PUSHING;
	private int nextSlice;
	private boolean spiralSpent;
	private final Set<UUID> damaged = new HashSet<>();
	private final List<LivingEntity> carried = new ArrayList<>();

	public WaterGimbal(Player player) {
		super(player);
		if (!bPlayer.canBendIgnoreBinds(this)) {
			return;
		}
		if (JCMethods.isLunarEclipse(player.getWorld())) {
			return;
		}
		if (hasAbility(player, WaterGimbal.class)) {
			return;
		}
		setFields();
		usingBottle = false;
		if (grabSource()) {
			start();
			initializing = true;
			if (hasAbility(player, Torrent.class)) {
				getAbility(player, Torrent.class).remove();
			}
			if (hasAbility(player, WaterManipulation.class)) {
				getAbility(player, WaterManipulation.class).remove();
			}
		}
	}
	
	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);

		sourceRange = config.getInt("Abilities.Water.WaterCombo.WaterGimbal.SourceRange");
		cooldown = config.getLong("Abilities.Water.WaterCombo.WaterGimbal.Cooldown");
		ringSize = config.getDouble("Abilities.Water.WaterCombo.WaterGimbal.RingSize");
		range = config.getDouble("Abilities.Water.WaterCombo.WaterGimbal.Range");
		damage = config.getDouble("Abilities.Water.WaterCombo.WaterGimbal.Damage");
		speed = config.getDouble("Abilities.Water.WaterCombo.WaterGimbal.Speed");
		animSpeed = config.getInt("Abilities.Water.WaterCombo.WaterGimbal.AnimationSpeed");
		plantSourcing = config.getBoolean("Abilities.Water.WaterCombo.WaterGimbal.PlantSource");
		snowSourcing = config.getBoolean("Abilities.Water.WaterCombo.WaterGimbal.SnowSource");
		requireAdjacentPlants = config.getBoolean("Abilities.Water.WaterCombo.WaterGimbal.RequireAdjacentPlants");
		canUseBottle = config.getBoolean("Abilities.Water.WaterCombo.WaterGimbal.BottleSource");
		abilityCollisionRadius = config.getDouble("Abilities.Water.WaterCombo.WaterGimbal.AbilityCollisionRadius");
		entityCollisionRadius = config.getDouble("Abilities.Water.WaterCombo.WaterGimbal.EntityCollisionRadius");
		requireAdjacentSources = config.getBoolean("Abilities.Water.WaterCombo.WaterGimbal.RequireMultipleAdjacentSources", false);
		spiralRange = config.getDouble(SPIRAL_PATH + "Range");
		spiralDamage = config.getDouble(SPIRAL_PATH + "Damage");
		spiralSpeed = config.getDouble(SPIRAL_PATH + "Speed");
		pushStrength = config.getDouble(SPIRAL_PATH + "Push.Strength");
		pushDistance = config.getDouble(SPIRAL_PATH + "Push.Distance");
		
		applyModifiers();
	}

	private void applyModifiers() {
		cooldown -= ((long) getNightFactor(cooldown) - cooldown);
		range = getNightFactor(range);
		damage = getNightFactor(damage);
		spiralRange = getNightFactor(spiralRange);
		spiralDamage = getNightFactor(spiralDamage);
	}

	@Override
	public void progress() {
		if (player == null || player.isDead() || !player.isOnline()) {
			remove();
			return;
		}
		if (firing) {
			progressSpiral();
			return;
		}
		if (!player.isSneaking()) {
			if (!startSpiral()) {
				remove();
			}
			return;
		}
		if (!bPlayer.canBendIgnoreBinds(this) || !bPlayer.canBendIgnoreCooldowns(getAbility("WaterManipulation"))) {
			remove();
			return;
		}
		if (hasAbility(player, WaterManipulation.class)) {
			getAbility(player, WaterManipulation.class).remove();
		}
		if (leftConsumed && rightConsumed) {
			remove();
			return;
		}
		if (!initializing) {
			getGimbalBlocks(player.getLocation());
			if (!leftVisible && !leftConsumed && origin1 != null) {
				if (origin1.getBlockY() <= player.getEyeLocation().getBlockY()) {
					new WaterBlast(player, origin1, range, damage, speed, entityCollisionRadius, abilityCollisionRadius, this);
					leftConsumed = true;
				}
			}

			if (!rightVisible && !rightConsumed && origin2 != null) {
				if (origin2.getBlockY() <= player.getEyeLocation().getBlockY()) {
					new WaterBlast(player, origin2, range, damage, speed, entityCollisionRadius, abilityCollisionRadius, this);
					rightConsumed = true;
				}
			}
		} else {
			Vector direction = GeneralMethods.getDirection(sourceLoc, player.getEyeLocation());
			sourceLoc = sourceLoc.add(direction.multiply(1).normalize());

			if (source == null || !sourceLoc.getBlock().getLocation().equals(source.getLocation())) {
				if (source != null) {
					source.revertBlock();
				}
				if (isTransparent(sourceLoc.getBlock())) {
					source = new TempBlock(sourceLoc.getBlock(), Material.WATER.createBlockData(bd -> ((Levelled) bd).setLevel(0)));
				}
			}

			if (source != null && source.getLocation().distance(player.getLocation()) < 2.5) {
				source.revertBlock();
				initializing = false;
			}
		}
	}

	private boolean grabSource() {
		sourceBlock = BlockSource.getWaterSourceBlock(player, sourceRange, ClickType.SHIFT_DOWN, true, true, plantSourcing, snowSourcing, false);
		if (sourceBlock != null) {
			// All of these extra checks need to be done because PK sourcing system is buggy.
			boolean usingSnow = snowSourcing && (sourceBlock.getType() == Material.SNOW_BLOCK || sourceBlock.getType() == Material.SNOW);

			if (isPlant(sourceBlock) || usingSnow) {
				if (usingSnow || !requireAdjacentPlants || JCMethods.isAdjacentToThreeOrMoreSources(sourceBlock, sourceBlock.getType())) {
					playFocusWaterEffect(sourceBlock);
					sourceLoc = sourceBlock.getLocation();

					new PlantRegrowth(this.player, sourceBlock);
					sourceBlock.setType(Material.AIR);

					return true;
				}
			} else if (!ElementalAbility.isSnow(sourceBlock)) {
				boolean isTempBlock = TempBlock.isTempBlock(sourceBlock);

				if (!requireAdjacentSources || GeneralMethods.isAdjacentToThreeOrMoreSources(sourceBlock, false) || (isTempBlock && WaterAbility.isBendableWaterTempBlock(sourceBlock))) {
					playFocusWaterEffect(sourceBlock);
					sourceLoc = sourceBlock.getLocation();

					if (isTempBlock) {
						PhaseChange.thaw(sourceBlock);
					}

					return true;
				}
			}
		}

		// Try to use bottles if no source blocks nearby.
		// todo: works the first time, requires actual sources and still consumes water bottle afterwards
		if (canUseBottle && hasWaterBottle(player)){
			Location eye = player.getEyeLocation();
			Location forward = eye.clone().add(eye.getDirection());

			if (isTransparent(eye.getBlock()) && isTransparent(forward.getBlock())) {
				sourceLoc = forward;
				sourceBlock = sourceLoc.getBlock();
				usingBottle = true;
				WaterReturn.emptyWaterBottle(player);
				return true;
			}
		}
		return false;
	}

	// Custom function to see if player has bottle.
	// This is to get around the WaterReturn limitation since OctopusForm will currently be using the bottle.
	private boolean hasWaterBottle(Player player) {
		PlayerInventory inventory = player.getInventory();
		return JedCore.plugin.getPotionEffectAdapter().hasWaterPotion(inventory);
	}

	public static void prepareBlast(Player player) {
		if (hasAbility(player, WaterGimbal.class)) {
			getAbility(player, WaterGimbal.class).prepareBlast();
			if (hasAbility(player, WaterManipulation.class)) {
				getAbility(player, WaterManipulation.class).remove();
			}
		}
	}

	public void prepareBlast() {
		if (firing) {
			return;
		}
		if (leftVisible) {
			leftVisible = false;
			return;
		}
		if (rightVisible) {
			rightVisible = false;
		}
	}

	private void getGimbalBlocks(Location location) {
		List<Block> ring1 = new ArrayList<>();
		List<Block> ring2 = new ArrayList<>();
		Location l = location.clone().add(0, 1, 0);
		int count = 0;

		while (count < animSpeed) {
			boolean completed = false;
			double velocity = 0.15;
			double angle =  3.0 + this.step * velocity;
			double xRotation = Math.PI / 2.82 * 2.1;
			Vector v1 = new Vector(Math.cos(angle), Math.sin(angle), 0.0D).multiply(ringSize);
			Vector v2 = new Vector(Math.cos(angle), Math.sin(angle), 0.0D).multiply(ringSize);
			rotateAroundAxisX(v1, xRotation);
			rotateAroundAxisX(v2, -xRotation);
			rotateAroundAxisY(v1, -((location.getYaw() * Math.PI / 180) - 1.575));
			rotateAroundAxisY(v2, -((location.getYaw() * Math.PI / 180) - 1.575));

			if (!ring1.contains(l.clone().add(v1).getBlock()) && !leftConsumed) {
				completed = true;
				Block block = l.clone().add(v1).getBlock();
				if (isTransparent(block)) {
					ring1.add(block);
				} else {
					for (int i = 0; i < 4; i++) {
						if (isTransparent(block.getRelative(BlockFace.UP, i))) {
							ring1.add(block.getRelative(BlockFace.UP, i));
							break;
						}
					}
				}
			}

			if (!ring2.contains(l.clone().add(v2).getBlock()) && !rightConsumed) {
				completed = true;
				Block block = l.clone().add(v2).getBlock();
				if (isTransparent(block)) {
					ring2.add(block);
				} else {
					for (int i = 0; i < 4; i++) {
						if (isTransparent(block.getRelative(BlockFace.UP, i))) {
							ring2.add(block.getRelative(BlockFace.UP, i));
							break;
						}
					}
				}
			}

			if (completed) {
				count++;
			}

			if (leftConsumed && rightConsumed) {
				break;
			}

			this.step++;
		}

		if (!leftConsumed) {
			if (!ring1.isEmpty()) {
				Collections.reverse(ring1);
				origin1 = ring1.get(0).getLocation();
			}
			for (Block block : ring1) {
				new RegenTempBlock(block, Material.WATER, Material.WATER.createBlockData(bd -> ((Levelled) bd).setLevel(0)), 150L);
				if (rand.nextInt(10) == 0) {
					playWaterbendingSound(block.getLocation());
				}
			}
		}

		if (!rightConsumed) {
			if (!ring2.isEmpty()) {
				Collections.reverse(ring2);
				origin2 = ring2.get(0).getLocation();
			}
			for (Block block : ring2) {
				new RegenTempBlock(block, Material.WATER, Material.WATER.createBlockData(bd -> ((Levelled) bd).setLevel(0)), 150L);
				if (rand.nextInt(10) == 0) {
					playWaterbendingSound(block.getLocation());
				}
			}
		}
	}

	private boolean startSpiral() {
		ConfigurationSection config = JedCoreConfig.getConfig(player);

		if (!config.getBoolean(SPIRAL_PATH + "Enabled")) {
			return false;
		}
		if (initializing || !leftVisible || !rightVisible || leftConsumed || rightConsumed || origin1 == null
				|| origin2 == null) {
			return false;
		}

		Location first = origin1.clone().add(0.5, 0.5, 0.5);
		Location second = origin2.clone().add(0.5, 0.5, 0.5);
		if (first.getWorld() != second.getWorld()) {
			return false;
		}

		Location start = clearedStart(new Location(first.getWorld(), (first.getX() + second.getX()) / 2.0,
				(first.getY() + second.getY()) / 2.0, (first.getZ() + second.getZ()) / 2.0));
		if (start == null) {
			return false;
		}

		if (spiralRange <= 0.0 || spiralSpeed <= 0.0) {
			return false;
		}

		Location target = GeneralMethods.getTargetedLocation(player, spiralRange, Material.WATER);
		Vector direction = target == null || target.getWorld() != start.getWorld()
				? player.getEyeLocation().getDirection()
				: target.toVector().subtract(start.toVector());
		if (!Frame.isFiniteNonZero(direction)) {
			direction = player.getEyeLocation().getDirection();
		}

		spiralFrame = Frame.of(direction, first.toVector().subtract(second.toVector()));
		spiralCenter = start.toVector();
		spiralPlan = Plan.create(spiralCenter, spiralFrame, spiralRange);
		if (spiralPlan == null) {
			JedCore.logDebug("WaterGimbal spiral geometry could not be solved.");
			return false;
		}

		trailRevertTime = config.getLong(SPIRAL_PATH + "Trail.RevertTime");
		sampleSpacing = Math.min(1.0, Math.max(0.05, config.getDouble(SPIRAL_PATH + "Collision.SampleSpacing")));
		pushEnabled = config.getBoolean(SPIRAL_PATH + "Push.Enabled");
		pushLift = config.getDouble(SPIRAL_PATH + "Push.Lift");

		spiralLocation = start;
		firing = true;
		return true;
	}

	private void progressSpiral() {
		if (player.getWorld() != spiralLocation.getWorld()) {
			remove();
			return;
		}

		double travel = Math.min(spiralSpeed, spiralRange - axialDistance);
		if (!Double.isFinite(travel) || travel <= 0.0) {
			remove();
			return;
		}

		int layers = Math.min(MAX_SAMPLE_LAYERS_PER_TICK, Math.max(1, (int) Math.ceil(travel / sampleSpacing)));
		double step = travel / layers;

		for (int layer = 0; layer < layers; layer++) {
			axialDistance = Math.min(spiralRange, axialDistance + step);

			if (!centerlineIsClear(axialDistance)) {
				remove();
				return;
			}

			World world = spiralLocation.getWorld();
			renderSlices(world);
			spiralLocation = spiralPoint(axialDistance, 1.0).toLocation(world);

			if (axialDistance >= nextSoundDistance) {
				playWaterbendingSound(spiralLocation);
				nextSoundDistance = axialDistance + SOUND_SPACING;
			}
			if (axialDistance >= MIN_HIT_DISTANCE) {
				hitAt(spiralLocation);
				hitAt(spiralPoint(axialDistance, -1.0).toLocation(world));
			}
			if (spiralSpent || (pushEndDistance != NOT_PUSHING && axialDistance >= pushEndDistance)) {
				remove();
				return;
			}
		}

		dragCarried();

		if (axialDistance >= spiralRange) {
			remove();
		}
	}

	private void renderSlices(World world) {
		while (nextSlice < spiralPlan.slices.size()) {
			Slice slice = spiralPlan.slices.get(nextSlice);

			if (slice.entryDistance > axialDistance + 1.0E-9) {
				break;
			}

			placeTrail(world.getBlockAt(slice.positive.x, slice.positive.y, slice.positive.z));
			placeTrail(world.getBlockAt(slice.negative.x, slice.negative.y, slice.negative.z));
			nextSlice++;
		}
	}

	private void placeTrail(Block block) {
		if (!GeneralMethods.isSolid(block) && isTransparent(block)
				&& !RegionProtection.isRegionProtected(this, block.getLocation())) {
			new TempBlock(block, sourceWater(), trailRevertTime, this);
		}
	}

	private void hitAt(Location location) {
		AABB collider = AABB.BlockBounds.at(location).scale(entityCollisionRadius * 2.0);

		CollisionDetector.checkEntityCollisions(player, location.getWorld(), collider, (entity) -> {
			LivingEntity target = (LivingEntity) entity;

			if (isProtected(target) || !damaged.add(target.getUniqueId())) {
				return false;
			}

			DamageHandler.damageEntity(target, spiralDamage, this);

			if (!pushEnabled) {
				spiralSpent = true;
				return false;
			}

			carried.add(target);

			if (pushEndDistance == NOT_PUSHING) {
				pushEndDistance = axialDistance + pushDistance;
			}
			return false;
		});
	}

	private void dragCarried() {
		if (carried.isEmpty()) {
			return;
		}

		Vector push = spiralFrame.axis.clone().multiply(pushStrength).add(new Vector(0.0, pushLift, 0.0));
		Iterator<LivingEntity> iterator = carried.iterator();

		while (iterator.hasNext()) {
			LivingEntity entity = iterator.next();

			if (!entity.isValid() || entity.isDead() || isProtected(entity)) {
				iterator.remove();
				continue;
			}
			GeneralMethods.setVelocity(this, entity, push.clone());
		}
	}

	private boolean isProtected(LivingEntity entity) {
		return RegionProtection.isRegionProtected(this, entity.getLocation())
				|| (entity instanceof Player && Commands.invincible.contains(entity.getName()));
	}

	private boolean centerlineIsClear(double distance) {
		Location axisPoint = spiralCenter.clone().add(spiralFrame.axis.clone().multiply(distance))
				.toLocation(spiralLocation.getWorld());

		if (RegionProtection.isRegionProtected(this, axisPoint)) {
			return false;
		}

		Block block = axisPoint.getBlock();
		return !GeneralMethods.isSolid(block) || !GeneralMethods.isSolid(block.getRelative(BlockFace.UP));
	}

	private Location clearedStart(Location start) {
		for (int up = 0; up <= START_CLEARANCE; up++) {
			Location candidate = start.clone().add(0.0, up, 0.0);
			Block block = candidate.getBlock();

			if (!GeneralMethods.isSolid(block) && isTransparent(block)) {
				return candidate;
			}
		}
		return null;
	}

	private Vector spiralPoint(double distance, double strandSign) {
		double angle = Math.PI * 2.0 * distance / Plan.CYCLE_LENGTH;
		Vector orbit = spiralFrame.radial.clone().multiply(Math.cos(angle))
				.add(spiralFrame.binormal.clone().multiply(Math.sin(angle))).multiply(Plan.RADIUS * strandSign);

		return spiralCenter.clone().add(spiralFrame.axis.clone().multiply(distance)).add(orbit);
	}

	private static BlockData sourceWater() {
		if (sourceWater == null) {
			sourceWater = Material.WATER.createBlockData(bd -> ((Levelled) bd).setLevel(0));
		}
		return sourceWater;
	}

	private void rotateAroundAxisX(Vector v, double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double y = v.getY() * cos - v.getZ() * sin;
		double z = v.getY() * sin + v.getZ() * cos;
		v.setY(y).setZ(z);
	}

	private void rotateAroundAxisY(Vector v, double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		double x = v.getX() * cos + v.getZ() * sin;
		double z = v.getX() * -sin + v.getZ() * cos;
		v.setX(x).setZ(z);
	}

	@Override
	public void remove() {
		if (source != null) {
			source.revertBlock();
		}
		if (player.isOnline() && !initializing) {
			bPlayer.addCooldown(this);
		}

		if (usingBottle) {
			new WaterReturn(player, sourceBlock);
		}
		super.remove();
	}
	
	public Player getPlayer() {
		return player;
	}
	
	@Override
	public long getCooldown() {
		return cooldown;
	}

	@Override
	public Location getLocation() {
		return spiralLocation;
	}

	@Override
	public List<Location> getLocations() {
		if (!firing || spiralLocation == null || spiralFrame == null || spiralCenter == null) {
			return Collections.emptyList();
		}

		List<Location> locations = new ArrayList<>(2);
		locations.add(spiralLocation);
		locations.add(spiralPoint(axialDistance, -1.0).toLocation(spiralLocation.getWorld()));
		return locations;
	}

	@Override
	public double getCollisionRadius() {
		return abilityCollisionRadius;
	}

	@Override
	public String getName() {
		return "WaterGimbal";
	}
	
	@Override
	public boolean isHiddenAbility() {
		return false;
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
	public Object createNewComboInstance(Player player) {
		return new WaterGimbal(player);
	}

	@Override
	public ArrayList<AbilityInformation> getCombination() {
		return ComboUtil.generateCombinationFromList(this, JedCoreConfig.getConfig(player).getStringList("Abilities.Water.WaterCombo.WaterGimbal.Combination"));
	}

	@Override
	public String getInstructions() {
		return JedCoreConfig.getConfig(player).getString("Abilities.Water.WaterCombo.WaterGimbal.Instructions");
	}

	@Override
	public String getDescription() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
	   return "* JedCore Addon *\n" + config.getString("Abilities.Water.WaterCombo.WaterGimbal.Description");
	}
	
	@Override
	public String getAuthor() {
		return JedCore.dev;
	}

	@Override
	public String getVersion() {
		return JedCore.version;
	}

	public int getSourceRange() {
		return sourceRange;
	}

	public void setSourceRange(int sourceRange) {
		this.sourceRange = sourceRange;
	}

	public void setCooldown(long cooldown) {
		this.cooldown = cooldown;
	}

	public double getRingSize() {
		return ringSize;
	}

	public void setRingSize(double ringSize) {
		this.ringSize = ringSize;
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

	public double getSpeed() {
		return speed;
	}

	public void setSpeed(double speed) {
		this.speed = speed;
	}

	public int getAnimSpeed() {
		return animSpeed;
	}

	public void setAnimSpeed(int animSpeed) {
		this.animSpeed = animSpeed;
	}

	public boolean isPlantSourcing() {
		return plantSourcing;
	}

	public void setPlantSourcing(boolean plantSourcing) {
		this.plantSourcing = plantSourcing;
	}

	public boolean isSnowSourcing() {
		return snowSourcing;
	}

	public void setSnowSourcing(boolean snowSourcing) {
		this.snowSourcing = snowSourcing;
	}

	public boolean isRequireAdjacentPlants() {
		return requireAdjacentPlants;
	}

	public void setRequireAdjacentPlants(boolean requireAdjacentPlants) {
		this.requireAdjacentPlants = requireAdjacentPlants;
	}

	public boolean isCanUseBottle() {
		return canUseBottle;
	}

	public void setCanUseBottle(boolean canUseBottle) {
		this.canUseBottle = canUseBottle;
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

	public int getStep() {
		return step;
	}

	public void setStep(int step) {
		this.step = step;
	}

	public boolean isInitializing() {
		return initializing;
	}

	public void setInitializing(boolean initializing) {
		this.initializing = initializing;
	}

	public boolean isLeftVisible() {
		return leftVisible;
	}

	public void setLeftVisible(boolean leftVisible) {
		this.leftVisible = leftVisible;
	}

	public boolean isRightVisible() {
		return rightVisible;
	}

	public void setRightVisible(boolean rightVisible) {
		this.rightVisible = rightVisible;
	}

	public boolean isRightConsumed() {
		return rightConsumed;
	}

	public void setRightConsumed(boolean rightConsumed) {
		this.rightConsumed = rightConsumed;
	}

	public boolean isLeftConsumed() {
		return leftConsumed;
	}

	public void setLeftConsumed(boolean leftConsumed) {
		this.leftConsumed = leftConsumed;
	}

	public Block getSourceBlock() {
		return sourceBlock;
	}

	public void setSourceBlock(Block sourceBlock) {
		this.sourceBlock = sourceBlock;
	}

	public TempBlock getSource() {
		return source;
	}

	public void setSource(TempBlock source) {
		this.source = source;
	}

	public Location getSourceLoc() {
		return sourceLoc;
	}

	public void setSourceLoc(Location sourceLoc) {
		this.sourceLoc = sourceLoc;
	}

	public Location getOrigin1() {
		return origin1;
	}

	public void setOrigin1(Location origin1) {
		this.origin1 = origin1;
	}

	public Location getOrigin2() {
		return origin2;
	}

	public void setOrigin2(Location origin2) {
		this.origin2 = origin2;
	}

	public boolean isUsingBottle() {
		return usingBottle;
	}

	public void setUsingBottle(boolean usingBottle) {
		this.usingBottle = usingBottle;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}
	
	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Water.WaterCombo.WaterGimbal.Enabled");
	}

	public static void applyAvatarStateModifier(AbilityRecalculateAttributeEvent event) {
		WaterGimbal ability = (WaterGimbal) event.getAbility();

		if (ability.bPlayer == null || !ability.bPlayer.isAvatarState()) {
			return;
		}

		String source = getInheritedAttribute(event.getAttribute());
		if (source == null) {
			return;
		}

		Map<String, AttributeCache> attributes = CoreAbility.getAttributeCache(ability);
		AttributeCache target = attributes.get(event.getAttribute());
		AttributeCache inherited = attributes.get(source);

		if (target == null || inherited == null || target.getAvatarStateModifier().isPresent()) {
			return;
		}

		if (inherited.getAvatarStateModifier().isPresent()) {
			event.addModification(inherited.getAvatarStateModifier().get());
		}
	}

	private static String getInheritedAttribute(String attribute) {
		if (attribute.equals(SPIRAL_DAMAGE_ATTRIBUTE)) {
			return Attribute.DAMAGE;
		}
		if (attribute.equals(SPIRAL_RANGE_ATTRIBUTE) || attribute.equals(SPIRAL_PUSH_DISTANCE_ATTRIBUTE)) {
			return Attribute.RANGE;
		}
		if (attribute.equals(SPIRAL_SPEED_ATTRIBUTE)) {
			return Attribute.SPEED;
		}
		return null;
	}

	private static class Frame {
		private static final double EPSILON_SQUARED = 1.0E-12;

		private final Vector axis;
		private final Vector radial;
		private final Vector binormal;

		private Frame(Vector axis, Vector radial, Vector binormal) {
			this.axis = axis;
			this.radial = radial;
			this.binormal = binormal;
		}

		private static Frame of(Vector direction, Vector preferredRadial) {
			Vector axis = normalized(direction);
			Vector radial = preferredRadial.clone();
			radial.subtract(axis.clone().multiply(radial.dot(axis)));

			if (!isFiniteNonZero(radial)) {
				Vector fallback = Math.abs(axis.getY()) < 0.9 ? new Vector(0.0, 1.0, 0.0) : new Vector(1.0, 0.0, 0.0);
				radial = fallback.subtract(axis.clone().multiply(fallback.dot(axis)));
			}
			radial = normalized(radial);

			return new Frame(axis, radial, normalized(axis.clone().crossProduct(radial)));
		}

		private static Vector normalized(Vector value) {
			if (!isFiniteNonZero(value)) {
				throw new IllegalArgumentException("Spiral frame needs finite non-zero vectors.");
			}
			return value.clone().normalize();
		}

		private static boolean isFiniteNonZero(Vector vector) {
			return vector != null && Double.isFinite(vector.getX()) && Double.isFinite(vector.getY())
					&& Double.isFinite(vector.getZ()) && vector.lengthSquared() > EPSILON_SQUARED;
		}
	}

	private static class Plan {
		private static final double RADIUS = 1.0;
		private static final double CYCLE_LENGTH = 14.0;
		private static final int[][] GRID_RING = {{1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}};
		private static final int[] GRID_SECTION_THICKNESS = {1, 2, 3, 1, 1, 2, 3, 1};
		private static final double RAY_START_EPSILON = 1.0E-7;
		private static final double BOUNDARY_EPSILON = 1.0E-9;
		private static final int MAX_SEARCH_STATES = 100_000;

		private final List<Slice> slices;

		private Plan(List<Slice> slices) {
			this.slices = Collections.unmodifiableList(slices);
		}

		private static Plan create(Vector center, Frame frame, double range) {
			List<CenterCell> centerline = traceCenterline(center, frame.axis, range);
			List<Section> sections = sections(centerline, frame);
			Offset[] choices = new Offset[sections.size()];

			if (!new Search(sections, choices).solve(0, null)) {
				return null;
			}

			List<Slice> slices = new ArrayList<>(centerline.size());
			for (int i = 0; i < sections.size(); i++) {
				for (CenterCell centerCell : sections.get(i).centers) {
					slices.add(new Slice(centerCell.entryDistance, centerCell.cell, choices[i]));
				}
			}
			return new Plan(slices);
		}

		private static List<CenterCell> traceCenterline(Vector origin, Vector axis, double range) {
			Vector start = origin.clone().add(axis.clone().multiply(RAY_START_EPSILON));
			int x = (int) Math.floor(start.getX());
			int y = (int) Math.floor(start.getY());
			int z = (int) Math.floor(start.getZ());
			int stepX = step(axis.getX());
			int stepY = step(axis.getY());
			int stepZ = step(axis.getZ());
			double deltaX = delta(axis.getX());
			double deltaY = delta(axis.getY());
			double deltaZ = delta(axis.getZ());
			double nextX = nextBoundaryDistance(origin.getX(), axis.getX(), x);
			double nextY = nextBoundaryDistance(origin.getY(), axis.getY(), y);
			double nextZ = nextBoundaryDistance(origin.getZ(), axis.getZ(), z);

			List<CenterCell> cells = new ArrayList<>();
			cells.add(new CenterCell(0.0, new Cell(x, y, z)));

			while (true) {
				double next = Math.min(nextX, Math.min(nextY, nextZ));

				if (!Double.isFinite(next) || next > range + BOUNDARY_EPSILON) {
					break;
				}
				if (Math.abs(nextX - next) <= BOUNDARY_EPSILON) {
					x += stepX;
					nextX += deltaX;
				}
				if (Math.abs(nextY - next) <= BOUNDARY_EPSILON) {
					y += stepY;
					nextY += deltaY;
				}
				if (Math.abs(nextZ - next) <= BOUNDARY_EPSILON) {
					z += stepZ;
					nextZ += deltaZ;
				}
				cells.add(new CenterCell(Math.max(0.0, next), new Cell(x, y, z)));
			}
			return cells;
		}

		private static List<Section> sections(List<CenterCell> centerline, Frame frame) {
			List<Section> sections = new ArrayList<>();
			int start = 0;
			int sectionIndex = 0;
			double sectionEnd = 0.0;

			while (start < centerline.size()) {
				sectionEnd += GRID_SECTION_THICKNESS[Math.floorMod(sectionIndex, GRID_SECTION_THICKNESS.length)];
				int end = start;

				while (end < centerline.size() && centerline.get(end).entryDistance < sectionEnd - BOUNDARY_EPSILON) {
					end++;
				}
				if (end == start) {
					end = start + 1;
					sectionEnd = centerline.get(start).entryDistance;
				}

				List<CenterCell> centers = new ArrayList<>(centerline.subList(start, end));
				sections.add(new Section(centers, candidates(centers, gridTarget(frame, sectionIndex), frame.axis)));
				start = end;
				sectionIndex++;
			}
			return sections;
		}

		private static List<Option> candidates(List<CenterCell> centers, Vector target, Vector axis) {
			List<Option> candidates = new ArrayList<>();

			for (int x = -1; x <= 1; x++) {
				for (int y = -1; y <= 1; y++) {
					for (int z = -1; z <= 1; z++) {
						if (x == 0 && y == 0 && z == 0) {
							continue;
						}
						Offset offset = new Offset(x, y, z);
						Option option = Option.create(centers, offset, score(offset, target),
								axialPenalty(offset, axis));

						if (option != null) {
							candidates.add(option);
						}
					}
				}
			}

			candidates.sort(Comparator.comparingDouble((Option option) -> option.score)
					.thenComparingDouble(option -> option.axialPenalty)
					.thenComparingInt(option -> option.offset.x)
					.thenComparingInt(option -> option.offset.y)
					.thenComparingInt(option -> option.offset.z));
			return candidates;
		}

		private static double score(Offset offset, Vector target) {
			double x = offset.x - target.getX();
			double y = offset.y - target.getY();
			double z = offset.z - target.getZ();
			return x * x + y * y + z * z;
		}

		private static double axialPenalty(Offset offset, Vector axis) {
			double length = Math.sqrt(offset.x * offset.x + offset.y * offset.y + offset.z * offset.z);
			return Math.abs((offset.x * axis.getX() + offset.y * axis.getY() + offset.z * axis.getZ()) / length);
		}

		private static int step(double component) {
			return component > 0.0 ? 1 : component < 0.0 ? -1 : 0;
		}

		private static double delta(double component) {
			return component == 0.0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(component);
		}

		private static double nextBoundaryDistance(double origin, double component, int cell) {
			if (component > 0.0) {
				return (cell + 1.0 - origin) / component;
			}
			if (component < 0.0) {
				return (origin - cell) / -component;
			}
			return Double.POSITIVE_INFINITY;
		}

		private static Vector gridTarget(Frame frame, int ringIndex) {
			int[] local = GRID_RING[Math.floorMod(ringIndex, GRID_RING.length)];
			return frame.radial.clone().multiply(local[0]).add(frame.binormal.clone().multiply(local[1]));
		}

		private static boolean touches(List<Cell> first, List<Cell> second) {
			for (Cell firstCell : first) {
				for (Cell secondCell : second) {
					int gap = Math.max(Math.abs(firstCell.x - secondCell.x),
							Math.max(Math.abs(firstCell.y - secondCell.y), Math.abs(firstCell.z - secondCell.z)));

					if (gap <= 1) {
						return true;
					}
				}
			}
			return false;
		}

		private static void removeLast(List<Cell> cells, int count) {
			for (int i = 0; i < count; i++) {
				cells.remove(cells.size() - 1);
			}
		}
	}

	private static class Slice {
		private final double entryDistance;
		private final Cell positive;
		private final Cell negative;

		private Slice(double entryDistance, Cell center, Offset offset) {
			this.entryDistance = entryDistance;
			this.positive = center.offset(offset);
			this.negative = center.offset(offset.negative());
		}
	}

	private static class Cell {
		private final int x;
		private final int y;
		private final int z;

		private Cell(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		private Cell offset(Offset offset) {
			return new Cell(x + offset.x, y + offset.y, z + offset.z);
		}
	}

	private static class Offset {
		private final int x;
		private final int y;
		private final int z;

		private Offset(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		private Offset negative() {
			return new Offset(-x, -y, -z);
		}

		private boolean isNeighbor(Offset other) {
			int movement = Math.max(Math.abs(x - other.x), Math.max(Math.abs(y - other.y), Math.abs(z - other.z)));
			return movement > 0 && movement <= 1;
		}
	}

	private static class CenterCell {
		private final double entryDistance;
		private final Cell cell;

		private CenterCell(double entryDistance, Cell cell) {
			this.entryDistance = entryDistance;
			this.cell = cell;
		}
	}

	private static class Section {
		private final List<CenterCell> centers;
		private final List<Option> options;

		private Section(List<CenterCell> centers, List<Option> options) {
			this.centers = centers;
			this.options = options;
		}
	}

	private static class Option {
		private final Offset offset;
		private final List<Cell> positive;
		private final List<Cell> negative;
		private final double score;
		private final double axialPenalty;

		private Option(Offset offset, List<Cell> positive, List<Cell> negative, double score, double axialPenalty) {
			this.offset = offset;
			this.positive = positive;
			this.negative = negative;
			this.score = score;
			this.axialPenalty = axialPenalty;
		}

		private static Option create(List<CenterCell> centers, Offset offset, double score, double axialPenalty) {
			List<Cell> positive = new ArrayList<>(centers.size());
			List<Cell> negative = new ArrayList<>(centers.size());

			for (CenterCell center : centers) {
				positive.add(center.cell.offset(offset));
				negative.add(center.cell.offset(offset.negative()));
			}

			if (Plan.touches(positive, negative)) {
				return null;
			}
			return new Option(offset, positive, negative, score, axialPenalty);
		}
	}

	private static class Search {
		private final List<Section> sections;
		private final Offset[] choices;
		private final List<Cell> positiveCells = new ArrayList<>();
		private final List<Cell> negativeCells = new ArrayList<>();
		private int states;

		private Search(List<Section> sections, Offset[] choices) {
			this.sections = sections;
			this.choices = choices;
		}

		private boolean solve(int sectionIndex, Offset previous) {
			if (++states > Plan.MAX_SEARCH_STATES) {
				return false;
			}
			if (sectionIndex >= sections.size()) {
				return true;
			}

			for (Option option : sections.get(sectionIndex).options) {
				if (previous != null && !previous.isNeighbor(option.offset)) {
					continue;
				}
				if (Plan.touches(option.positive, negativeCells) || Plan.touches(option.negative, positiveCells)) {
					continue;
				}

				choices[sectionIndex] = option.offset;
				positiveCells.addAll(option.positive);
				negativeCells.addAll(option.negative);

				if (solve(sectionIndex + 1, option.offset)) {
					return true;
				}

				Plan.removeLast(positiveCells, option.positive.size());
				Plan.removeLast(negativeCells, option.negative.size());
				choices[sectionIndex] = null;
			}
			return false;
		}
	}
}
