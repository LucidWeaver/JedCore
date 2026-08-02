package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.AttributeCache;
import com.projectkorra.projectkorra.earthbending.Collapse;
import com.projectkorra.projectkorra.util.ActionBar;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class EarthPillar extends EarthAbility implements AddonAbility {

	private static final ConcurrentHashMap<Block, EarthPillar> AFFECTED_BLOCKS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Block, EarthPillar> ACTIVE_SOURCES = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, AutoSession> AUTO_SESSIONS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<UUID, Mode> MODES = new ConcurrentHashMap<>();
	private static final AtomicLong PILLAR_SEQUENCE = new AtomicLong();

	private Block block;
	private Block sourceBlock;
	private BlockFace face;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute(Attribute.HEIGHT)
	private int height;
	@Attribute(Attribute.RANGE)
	private int range;
	@Attribute(Attribute.SPEED)
	private double speed;
	private int length;
	private int step;
	private double movementProgress;
	private int maxPillars;
	private long pillarSequence;

	private final List<Block> blocks = new ArrayList<>();

	public EarthPillar(Player player) {
		this(player, null, false);
	}

	private EarthPillar(Player player, Target autoTarget) {
		this(player, autoTarget, false);
	}

	private EarthPillar(Player player, Target autoTarget, boolean settingsOnly) {
		super(player);

		if (bPlayer == null || !bPlayer.canBendIgnoreCooldowns(this)) {
			return;
		}

		setFields();
		recalculateAttributes();
		if (height <= 0 || range <= 0 || !Double.isFinite(speed) || speed <= 0) {
			clearAttributeCache();
			return;
		}
		if (settingsOnly) {
			return;
		}

		boolean autoMode = autoTarget != null;
		Block target = autoMode ? autoTarget.block : BlockSource.getEarthSourceBlock(player, range, ClickType.SHIFT_DOWN);
		if (target == null
				|| autoMode && (!target.getWorld().equals(player.getWorld())
				|| target.getLocation().distanceSquared(player.getEyeLocation()) > range * (double) range
				|| !EarthAbility.isEarthbendable(player, target))) {
			clearAttributeCache();
			return;
		}

		EarthPillar affected = AFFECTED_BLOCKS.get(target);
		if (affected == null) {
			affected = ACTIVE_SOURCES.get(target);
		}
		if (affected != null) {
			if (!autoMode) {
				affected.revertPillar();
				playEarthbendingSound(target.getLocation());
			}
			clearAttributeCache();
			return;
		}

		if (!bPlayer.canBend(this)) {
			clearAttributeCache();
			return;
		}

		face = autoMode ? autoTarget.face : getTargetFace(player, target);
		sourceBlock = target;
		block = target;
		length = getEarthbendableBlocksLength(block, getDirection(face).clone().multiply(-1), height);
		if (length <= 0) {
			clearAttributeCache();
			return;
		}

		start();
		if (!isStarted()) {
			if (!isRemoved()) {
				clearAttributeCache();
			}
			return;
		}

		if (cooldown > 0) {
			bPlayer.addCooldown(this);
		}
		pillarSequence = PILLAR_SEQUENCE.incrementAndGet();
		ACTIVE_SOURCES.put(sourceBlock, this);
		enforcePillarLimit(player, maxPillars);
	}

	public static void activate(Player player) {
		UUID uuid = player.getUniqueId();
		if (getMode(player) == Mode.SINGLE) {
			AUTO_SESSIONS.remove(uuid);
			new EarthPillar(player);
			return;
		}

		ConfigurationSection config = JedCoreConfig.getConfig(player);
		if (!config.getBoolean("Abilities.Earth.EarthPillar.AutoMode.Enabled")) {
			MODES.remove(uuid);
			AUTO_SESSIONS.remove(uuid);
			showMode(player, Mode.SINGLE);
			new EarthPillar(player);
			return;
		}

		long interval = Math.max(0, config.getLong("Abilities.Earth.EarthPillar.AutoMode.Interval"));
		AutoSession session = new AutoSession(player, interval);
		if (session.start()) {
			AUTO_SESSIONS.put(uuid, session);
		}
	}

	public static void toggleMode(Player player) {
		UUID uuid = player.getUniqueId();
		AUTO_SESSIONS.remove(uuid);
		if (getMode(player) == Mode.WALL) {
			MODES.remove(uuid);
			showMode(player, Mode.SINGLE);
			return;
		}

		ConfigurationSection config = JedCoreConfig.getConfig(player);
		if (!config.getBoolean("Abilities.Earth.EarthPillar.AutoMode.Enabled")) {
			ActionBar.sendActionBar(ChatColor.RED + "EarthPillar: Wall Mode is disabled", player);
			return;
		}

		MODES.put(uuid, Mode.WALL);
		showMode(player, Mode.WALL);
	}

	private static Mode getMode(Player player) {
		return MODES.getOrDefault(player.getUniqueId(), Mode.SINGLE);
	}

	private static void showMode(Player player, Mode mode) {
		ChatColor color = mode == Mode.WALL ? ChatColor.GOLD : ChatColor.GREEN;
		String name = mode == Mode.WALL ? "Wall Mode" : "Single Mode";
		ActionBar.sendActionBar(color + "EarthPillar: " + name, player);
	}

	private static int getEffectiveRange(Player player) {
		EarthPillar settings = new EarthPillar(player, null, true);
		int effectiveRange = settings.range;
		settings.clearAttributeCache();
		return effectiveRange;
	}

	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);

		cooldown = Math.max(0, config.getLong("Abilities.Earth.EarthPillar.Cooldown"));
		height = config.getInt("Abilities.Earth.EarthPillar.Height");
		range = config.getInt("Abilities.Earth.EarthPillar.Range");
		speed = config.getDouble("Abilities.Earth.EarthPillar.Speed");
		maxPillars = Math.max(1, config.getInt("Abilities.Earth.EarthPillar.MaxPillars"));
	}

	@Override
	public void progress() {
		movementProgress += speed / 20.0;
		int moves = (int) movementProgress;
		movementProgress -= moves;

		while (moves-- > 0 && step < length) {
			if (!movePillar()) {
				if (blocks.isEmpty()) {
					ACTIVE_SOURCES.remove(sourceBlock, this);
				}
				remove();
				return;
			}
			step++;
		}

		if (step >= length) {
			remove();
		}
	}

	private boolean movePillar() {
		if (!moveEarth(block, getDirection(face), length)) {
			return false;
		}

		block = block.getRelative(face);
		AFFECTED_BLOCKS.put(block, this);
		blocks.add(block);
		return true;
	}

	private BlockFace getTargetFace(Player player, Block target) {
		List<Block> targetBlocks = player.getLastTwoTargetBlocks(getTransparentMaterialSet(), range);
		if (targetBlocks.size() > 1 && target.equals(targetBlocks.get(1))) {
			BlockFace targetFace = target.getFace(targetBlocks.get(0));
			if (targetFace != null) {
				return targetFace;
			}
		}

		Vector offset = player.getEyeLocation().toVector().subtract(target.getLocation().add(0.5, 0.5, 0.5).toVector());
		double x = Math.abs(offset.getX());
		double y = Math.abs(offset.getY());
		double z = Math.abs(offset.getZ());
		if (x >= y && x >= z) {
			return offset.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
		}
		if (y >= x && y >= z) {
			return offset.getY() >= 0 ? BlockFace.UP : BlockFace.DOWN;
		}
		return offset.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
	}

	private void revertPillar() {
		for (Block affectedBlock : new ArrayList<>(blocks)) {
			Collapse.revertBlock(affectedBlock);
			AFFECTED_BLOCKS.remove(affectedBlock, this);
		}
		blocks.clear();
		ACTIVE_SOURCES.remove(sourceBlock, this);
		if (!isRemoved()) {
			remove();
		}
	}

	private static void enforcePillarLimit(Player player, int limit) {
		UUID uuid = player.getUniqueId();
		List<EarthPillar> pillars = new ArrayList<>();
		for (EarthPillar pillar : ACTIVE_SOURCES.values()) {
			Player owner = pillar.getPlayer();
			if (owner != null && uuid.equals(owner.getUniqueId())) {
				pillars.add(pillar);
			}
		}
		if (pillars.size() <= limit) {
			return;
		}

		pillars.sort((first, second) -> Long.compare(first.pillarSequence, second.pillarSequence));
		for (int i = 0; i < pillars.size() - limit; i++) {
			pillars.get(i).revertPillar();
		}
	}

	private void clearAttributeCache() {
		for (AttributeCache cache : CoreAbility.getAttributeCache(this).values()) {
			cache.getInitialValues().remove(this);
			cache.getCurrentModifications().remove(this);
		}
	}

	private Vector getDirection(BlockFace face) {
		switch (face) {
			case UP:
				return new Vector(0, 1, 0);
			case DOWN:
				return new Vector(0, -1, 0);
			case NORTH:
				return new Vector(0, 0, -1);
			case SOUTH:
				return new Vector(0, 0, 1);
			case EAST:
				return new Vector(1, 0, 0);
			case WEST:
				return new Vector(-1, 0, 0);
			default:
				return null;
		}
	}

	public static void progressAll() {
		long now = System.currentTimeMillis();
		for (UUID uuid : AUTO_SESSIONS.keySet()) {
			AutoSession session = AUTO_SESSIONS.get(uuid);
			if (session != null && !session.progress(now)) {
				AUTO_SESSIONS.remove(uuid, session);
			}
		}

		for (Block block : AFFECTED_BLOCKS.keySet()) {
			EarthPillar pillar = AFFECTED_BLOCKS.get(block);
			if (pillar != null && !EarthAbility.isEarthbendable(pillar.getPlayer(), block) && AFFECTED_BLOCKS.remove(block, pillar)) {
				pillar.blocks.remove(block);
				if (pillar.blocks.isEmpty()) {
					ACTIVE_SOURCES.remove(pillar.sourceBlock, pillar);
				}
			}
		}
	}

	@Override
	public long getCooldown() {
		return cooldown;
	}

	public void setCooldown(long cooldown) {
		this.cooldown = cooldown;
	}

	@Override
	public Location getLocation() {
		return block != null ? block.getLocation() : null;
	}

	@Override
	public String getName() {
		return "EarthPillar";
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
		return "* JedCore Addon *\n" + config.getString("Abilities.Earth.EarthPillar.Description");
	}

	public Block getBlock() {
		return block;
	}

	public void setBlock(Block block) {
		this.block = block;
	}

	public BlockFace getFace() {
		return face;
	}

	public void setFace(BlockFace face) {
		this.face = face;
	}

	public int getHeight() {
		return height;
	}

	public void setHeight(int height) {
		this.height = height;
	}

	public int getRange() {
		return range;
	}

	public void setRange(int range) {
		this.range = range;
	}

	public double getSpeed() {
		return speed;
	}

	public void setSpeed(double speed) {
		this.speed = speed;
	}

	public int getLength() {
		return length;
	}

	public void setLength(int length) {
		this.length = length;
	}

	public int getStep() {
		return step;
	}

	public void setStep(int step) {
		this.step = step;
	}

	public List<Block> getBlocks() {
		return blocks;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {
		AUTO_SESSIONS.clear();
		MODES.clear();
		ACTIVE_SOURCES.clear();
		AFFECTED_BLOCKS.clear();
	}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Earth.EarthPillar.Enabled");
	}

	private static class Target {

		private final Block block;
		private final BlockFace face;

		private Target(Block block, BlockFace face) {
			this.block = block;
			this.face = face;
		}
	}

	private enum Mode {
		SINGLE,
		WALL
	}

	private static class AutoSession {

		private static final int MAX_PENDING = 128;

		private final Player player;
		private final World world;
		private final long interval;
		private final int range;
		private final double angularStep;
		private final Target initialTarget;
		private final ArrayDeque<Target> pending = new ArrayDeque<>();
		private final Set<Block> queuedBlocks = new HashSet<>();
		private long nextSampleAt;
		private float lastYaw;
		private float lastPitch;
		private Target lastTarget;
		private boolean sampled;
		private boolean released;

		private AutoSession(Player player, long interval) {
			this.player = player;
			world = player.getWorld();
			this.interval = interval;
			range = getEffectiveRange(player);
			angularStep = Math.toDegrees(Math.atan(1.0 / Math.max(1, range)));
			Location eye = player.getEyeLocation();
			lastYaw = eye.getYaw();
			lastPitch = eye.getPitch();
			initialTarget = range > 0 ? findTarget(lastYaw, lastPitch) : null;
		}

		private boolean start() {
			if (range <= 0) {
				return false;
			}
			sampleSweep();
			progressQueue();
			nextSampleAt = System.currentTimeMillis() + interval;
			return true;
		}

		private boolean progress(long now) {
			if (!isValid()) {
				return false;
			}

			boolean sneaking = player.isSneaking();
			if (!sneaking && !released) {
				sampleSweep();
				released = true;
			}

			if (sneaking && !released && now >= nextSampleAt) {
				sampleSweep();
				nextSampleAt = now + interval;
			}

			progressQueue();
			return sneaking || !pending.isEmpty();
		}

		private void sampleSweep() {
			Location eye = player.getEyeLocation();
			float currentYaw = eye.getYaw();
			float currentPitch = eye.getPitch();
			float yawChange = wrapDegrees(currentYaw - lastYaw);
			float pitchChange = currentPitch - lastPitch;
			int samples = Math.min(MAX_PENDING, Math.max(1, (int) Math.ceil(Math.max(Math.abs(yawChange), Math.abs(pitchChange)) / angularStep)));

			if (!sampled) {
				acceptTarget(initialTarget);
				sampled = true;
			}

			for (int i = 1; i <= samples; i++) {
				double progress = i / (double) samples;
				acceptTarget(findTarget((float) (lastYaw + yawChange * progress), (float) (lastPitch + pitchChange * progress)));
			}

			lastYaw = currentYaw;
			lastPitch = currentPitch;
		}

		private Target findTarget(float yaw, float pitch) {
			Location eye = player.getEyeLocation();
			eye.setYaw(yaw);
			eye.setPitch(pitch);
			BlockIterator iterator = new BlockIterator(world, eye.toVector(), eye.getDirection(), 0, range);
			Block previous = eye.getBlock();

			while (iterator.hasNext()) {
				Block candidate = iterator.next();
				if (candidate.equals(previous)) {
					continue;
				}
				EarthPillar affected = AFFECTED_BLOCKS.get(candidate);
				if (affected != null) {
					return affected.getPlayer().equals(player) ? new Target(affected.sourceBlock, affected.face) : null;
				}
				if (EarthAbility.isEarthbendable(player, candidate)) {
					BlockFace targetFace = candidate.getFace(previous);
					if (targetFace != null) {
						return new Target(candidate, targetFace);
					}
				}
				if (!EarthAbility.isTransparent(player, candidate)) {
					return null;
				}
				previous = candidate;
			}
			return null;
		}

		private void acceptTarget(Target target) {
			if (target == null) {
				lastTarget = null;
				return;
			}
			if (lastTarget == null) {
				enqueue(target);
			} else {
				enqueuePath(lastTarget, target);
			}
			lastTarget = target;
		}

		private void enqueuePath(Target from, Target to) {
			if (!from.block.getWorld().equals(to.block.getWorld()) || from.face != to.face) {
				enqueue(to);
				return;
			}

			int xChange = to.block.getX() - from.block.getX();
			int yChange = to.block.getY() - from.block.getY();
			int zChange = to.block.getZ() - from.block.getZ();
			int steps = Math.max(Math.abs(xChange), Math.max(Math.abs(yChange), Math.abs(zChange)));
			if (steps == 0) {
				return;
			}

			for (int i = 1; i <= steps; i++) {
				double progress = i / (double) steps;
				int x = (int) Math.round(from.block.getX() + xChange * progress);
				int y = (int) Math.round(from.block.getY() + yChange * progress);
				int z = (int) Math.round(from.block.getZ() + zChange * progress);
				Block candidate = world.getBlockAt(x, y, z);
				if (EarthAbility.isEarthbendable(player, candidate) && EarthAbility.isTransparent(player, candidate.getRelative(to.face))) {
					enqueue(new Target(candidate, to.face));
				}
			}
		}

		private void enqueue(Target target) {
			if (pending.size() >= MAX_PENDING
					|| AFFECTED_BLOCKS.containsKey(target.block)
					|| ACTIVE_SOURCES.containsKey(target.block)
					|| !queuedBlocks.add(target.block)) {
				return;
			}
			pending.add(target);
		}

		private void progressQueue() {
			BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
			if (bPlayer != null && bPlayer.isOnCooldown("EarthPillar")) {
				return;
			}

			Target target;
			while ((target = pending.poll()) != null) {
				queuedBlocks.remove(target.block);
				if (AFFECTED_BLOCKS.containsKey(target.block) || ACTIVE_SOURCES.containsKey(target.block)) {
					continue;
				}
				new EarthPillar(player, target);
				return;
			}
		}

		private float wrapDegrees(float degrees) {
			degrees %= 360;
			if (degrees >= 180) {
				degrees -= 360;
			}
			if (degrees < -180) {
				degrees += 360;
			}
			return degrees;
		}

		private boolean isValid() {
			if (range <= 0 || !player.isOnline() || player.isDead() || !player.getWorld().equals(world)) {
				return false;
			}

			BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
			CoreAbility ability = CoreAbility.getAbility(EarthPillar.class);
			ConfigurationSection config = JedCoreConfig.getConfig(player);
			return bPlayer != null
					&& ability != null
					&& getMode(player) == Mode.WALL
					&& "EarthPillar".equalsIgnoreCase(bPlayer.getBoundAbilityName())
					&& bPlayer.canCurrentlyBendWithWeapons()
					&& bPlayer.canBendIgnoreCooldowns(ability)
					&& config.getBoolean("Abilities.Earth.EarthPillar.Enabled")
					&& config.getBoolean("Abilities.Earth.EarthPillar.AutoMode.Enabled");
		}
	}
}
