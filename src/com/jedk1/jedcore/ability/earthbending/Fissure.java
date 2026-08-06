package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.region.RegionProtection;

import com.projectkorra.projectkorra.util.TempBlock;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Fissure extends LavaAbility implements AddonAbility {

	@Attribute(Attribute.RANGE)
	private int slapRange;
	@Attribute(Attribute.WIDTH)
	private int maxWidth;
	private long slapDelay;
	@Attribute(Attribute.DURATION)
	private long duration;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;

	private Location location;
	private Vector direction;
	private Vector blockDirection;
	private long time;
	private long step;
	private int slap;
	private int width;
	private boolean progressed;
	
	static Random rand = new Random();

	private final List<Step> centerSlap = new ArrayList<>();

	private static class Step {
		private final Block block;
		private final BlockFace face;

		private Step(Block block, BlockFace face) {
			this.block = block;
			this.face = face;
		}
	}
	private final List<Block> blocks = new ArrayList<>();
	private final List<TempBlock> tempblocks = new ArrayList<>();

	public Fissure(Player player) {
		super(player);
		
		if (!bPlayer.canBend(this) || hasAbility(player, Fissure.class) || !bPlayer.canLavabend()) {
			return;
		}

		setFields();
		time = System.currentTimeMillis();
		step = System.currentTimeMillis() + slapDelay;
		location = player.getLocation().clone();
		location.setPitch(0);
		start();
		if (isRemoved()) {
			return;
		}
		if (prepareLine()) {
			bPlayer.addCooldown(this);
		} else {
			remove();
		}
	}
	
	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		
		slapRange = config.getInt("Abilities.Earth.Fissure.SlapRange");
		maxWidth = config.getInt("Abilities.Earth.Fissure.MaxWidth");
		slapDelay = config.getInt("Abilities.Earth.Fissure.SlapDelay");
		duration = config.getInt("Abilities.Earth.Fissure.Duration");
		cooldown = config.getInt("Abilities.Earth.Fissure.Cooldown");
	}

	@Override
	public void progress() {
		if (player.isDead() || !player.isOnline()) {
			remove();
			return;
		}
		if (System.currentTimeMillis() > step && slap <= centerSlap.size()) {
			step = System.currentTimeMillis() + slapDelay;
			slapCenter();
			slap++;
		}
		if (System.currentTimeMillis() > time + duration) {
			remove();
		}
	}

	private boolean prepareLine() {
		direction = player.getEyeLocation().getDirection().setY(0).normalize();
		blockDirection = this.direction.clone().setX(Math.round(this.direction.getX()));
		blockDirection = blockDirection.setZ(Math.round(direction.getZ()));
		Location origin = player.getLocation().add(0, -1, 0).add(blockDirection.clone().multiply(2));
		if (!isEarthbendable(origin.getBlock())) {
			return false;
		}

		BlockFace cardinal = GeneralMethods.getCardinalDirection(blockDirection);
		BlockIterator bi = new BlockIterator(player.getWorld(), origin.toVector(), direction, 0, slapRange);
		Block previousColumn = origin.getBlock();
		int previousY = origin.getBlockY();
		int budget = slapRange;

		while (bi.hasNext() && budget > 0) {
			Block b = bi.next();
			Block start = b.getWorld().getBlockAt(b.getX(), previousY, b.getZ());

			if (start.getY() < start.getWorld().getMinHeight() || start.getY() >= start.getWorld().getMaxHeight()) {
				continue;
			}

			if (RegionProtection.isRegionProtected(this, start.getLocation())) {
				break;
			}

			Block surface = resolveSurface(start);
			if (surface == null) {
				break;
			}

			int dx = start.getX() - previousColumn.getX();
			int dz = start.getZ() - previousColumn.getZ();
			BlockFace forward = (dx == 0 && dz == 0) ? cardinal : GeneralMethods.getCardinalDirection(new Vector(dx, 0, dz));
			BlockFace backward = forward.getOppositeFace();
			int surfaceY = surface.getY();

			if (surfaceY > previousY) {
				for (int y = previousY + 1; y <= surfaceY && budget > 0; y++) {
					Block climbed = start.getWorld().getBlockAt(start.getX(), y, start.getZ());
					if (!isTransparent(climbed.getRelative(backward)) || !addStep(climbed, y == surfaceY ? BlockFace.UP : backward)) {
						return !centerSlap.isEmpty();
					}
					budget--;
				}
			} else if (surfaceY < previousY) {
				for (int y = previousY - 1; y > surfaceY && budget > 0; y--) {
					Block dropped = start.getWorld().getBlockAt(previousColumn.getX(), y, previousColumn.getZ());
					if (!isTransparent(dropped.getRelative(forward)) || !addStep(dropped, forward)) {
						return !centerSlap.isEmpty();
					}
					budget--;
				}
				if (budget > 0) {
					if (!addStep(surface, BlockFace.UP)) {
						return !centerSlap.isEmpty();
					}
					budget--;
				}
			} else {
				if (!addStep(surface, BlockFace.UP)) {
					return !centerSlap.isEmpty();
				}
				budget--;
			}

			previousColumn = start;
			previousY = surfaceY;
		}
		return !centerSlap.isEmpty();
	}

	private boolean addStep(Block block, BlockFace face) {
		if (!isEarthbendable(block) || RegionProtection.isRegionProtected(this, block.getLocation())) {
			return false;
		}

		centerSlap.add(new Step(block, face));
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

	private void slapCenter() {
		if (slap < centerSlap.size()) {
			Step center = centerSlap.get(slap);
			location = center.block.getLocation();
			addTempBlock(center.block, Material.LAVA);
		}
		if (slap >= centerSlap.size()) {
			progressed = true;
		}
	}
	
	public static void performAction(Player player) {
		if (hasAbility(player, Fissure.class)) {
			getAbility(player, Fissure.class).performAction();
		}
	}
	
	private void performAction() {
		if (width < maxWidth) {
			expandFissure();
		} else if (blocks.contains(player.getTargetBlock(null, 10))) {
			forceRevert();
		}
	}

	private void expandFissure() {
		if (progressed && width <= maxWidth) {
			width++;
			BlockFace leftFace = JCMethods.getLeftBlockFace(GeneralMethods.getCardinalDirection(blockDirection));
			BlockFace rightFace = leftFace.getOppositeFace();
			for (Step center : centerSlap) {
				expand(center.block.getRelative(leftFace, width), center.face);
				expand(center.block.getRelative(rightFace, width), center.face);
			}
		}
	}

	private void expand(Block block, BlockFace face) {
		if (block == null || block.getY() < block.getWorld().getMinHeight() || block.getY() >= block.getWorld().getMaxHeight()
				|| RegionProtection.isRegionProtected(this, block.getLocation())) {
			return;
		}

		Block side = block;
		if (face == BlockFace.UP) {
			if (!isEarthbendable(side)) {
				side = side.getRelative(BlockFace.DOWN);
			} else if (!isTransparent(side.getRelative(BlockFace.UP))) {
				side = side.getRelative(BlockFace.UP);
			}
		}

		Block surface = (isEarthbendable(side) && isTransparent(side.getRelative(face))) ? side : null;
		if (surface != null) {
			addTempBlock(surface, Material.LAVA);
		}
	}

	private void addTempBlock(Block block, Material material) {
		block.getLocation().getWorld().spawnParticle(Particle.LAVA, block.getLocation(), 0, 0, 0, 0, 1);
		playEarthbendingSound(block.getLocation());
		if (DensityShift.isPassiveSand(block)) {
            DensityShift.revertSand(block);
		}
		tempblocks.add(JCMethods.createTempBlock(block, material.createBlockData(), this));
		blocks.add(block);
	}

	private void forceRevert() {
		remove();
	}
	
	private void coolLava() {
		tempblocks.forEach(TempBlock::revertBlock);
		for (Block block : blocks) {
			JCMethods.createTempBlock(block, Material.STONE.createBlockData(), 500 + (long) rand.nextInt((int) 1000));
		}
		blocks.clear();
		tempblocks.clear();
	}

	@Override
	public void remove() {
		coolLava();
		super.remove();
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
		return "Fissure";
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
		return "* JedCore Addon *\n" + config.getString("Abilities.Earth.Fissure.Description");
	}

	public int getSlapRange() {
		return slapRange;
	}

	public void setSlapRange(int slapRange) {
		this.slapRange = slapRange;
	}

	public int getMaxWidth() {
		return maxWidth;
	}

	public void setMaxWidth(int maxWidth) {
		this.maxWidth = maxWidth;
	}

	public long getSlapDelay() {
		return slapDelay;
	}

	public void setSlapDelay(long slapDelay) {
		this.slapDelay = slapDelay;
	}

	public long getDuration() {
		return duration;
	}

	public void setDuration(long duration) {
		this.duration = duration;
	}

	public void setCooldown(long cooldown) {
		this.cooldown = cooldown;
	}

	public void setLocation(Location location) {
		this.location = location;
	}

	public Vector getDirection() {
		return direction;
	}

	public void setDirection(Vector direction) {
		this.direction = direction;
	}

	public Vector getBlockDirection() {
		return blockDirection;
	}

	public void setBlockDirection(Vector blockDirection) {
		this.blockDirection = blockDirection;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}

	public long getStep() {
		return step;
	}

	public void setStep(long step) {
		this.step = step;
	}

	public int getSlap() {
		return slap;
	}

	public void setSlap(int slap) {
		this.slap = slap;
	}

	public int getWidth() {
		return width;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	public boolean isProgressed() {
		return progressed;
	}

	public void setProgressed(boolean progressed) {
		this.progressed = progressed;
	}

	public List<Location> getCenterSlap() {
		List<Location> locations = new ArrayList<>(centerSlap.size());
		for (Step center : centerSlap) {
			locations.add(center.block.getLocation());
		}
		return locations;
	}

	public List<Block> getBlocks() {
		return blocks;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Earth.Fissure.Enabled");
	}
}
