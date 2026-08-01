package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.MetalAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.DamageHandler;

import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;

public class MetalFragments extends MetalAbility implements AddonAbility {

	@Attribute("MaxSources")
	private int maxSources;
	@Attribute(Attribute.SELECT_RANGE)
	private int selectRange;
	@Attribute("MaxShots")
	private int maxFragments;
	@Attribute(Attribute.DAMAGE)
	private double damage;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	private double velocity;

	public List<Block> sources = new ArrayList<>();
	private final List<Item> thrownFragments = new ArrayList<>();
	private final List<TempBlock> tblockTracker = new ArrayList<>();
	//private List<FallingBlock> fblockTracker = new ArrayList<>();
	private final HashMap<Block, Integer> counters = new HashMap<>();
	private final HashMap<TempFallingBlock, SourceReservation> pendingSources = new HashMap<>();
	private final HashMap<TempBlock, SourceReservation> sourceReservations = new HashMap<>();

	public MetalFragments(Player player) {
		super(player);
		
		if (hasAbility(player, MetalFragments.class)) {
			MetalFragments.selectAnotherSource(player);
			return;
		}

		if (!bPlayer.canBend(this) || !bPlayer.canMetalbend()) {
			return;
		}
		
		setFields();

		if (!hasSourceCapacity()) {
			return;
		}

		if (prepare()) {
			Block b = selectSource();
			if (RegionProtection.isRegionProtected(player, b.getLocation(), this)) {
				return;
			}

			start();
			if (!isRemoved()) {
				translateUpward(b);
			}
		}
	}
	
	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		
		maxSources = config.getInt("Abilities.Earth.MetalFragments.MaxSources");
		selectRange = config.getInt("Abilities.Earth.MetalFragments.SourceRange");
		maxFragments = config.getInt("Abilities.Earth.MetalFragments.MaxFragments");
		damage = config.getDouble("Abilities.Earth.MetalFragments.Damage");
		cooldown = config.getInt("Abilities.Earth.MetalFragments.Cooldown");
		velocity = config.getDouble("Abilities.Earth.MetalFragments.Velocity");
	}

	public static void shootFragment(Player player) {
		if (hasAbility(player, MetalFragments.class)) {
			getAbility(player, MetalFragments.class).shootFragment();
		}
	}

	private void shootFragment() {
		if (sources.size() <= 0)
			return;

		Random randy = new Random();
		int i = randy.nextInt(sources.size());
		Block source = sources.get(i);
		ItemStack is;

		switch (source.getType()) {
		case GOLD_BLOCK:
		case GOLD_ORE:
			is = new ItemStack(Material.GOLD_INGOT, 1);
			break;
		case COAL_BLOCK:
			is = new ItemStack(Material.COAL, 1);
			break;
		case COAL_ORE:
			is = new ItemStack(Material.COAL_ORE, 1);
			break;
		default:
			is = new ItemStack(Material.IRON_INGOT, 1);
			break;
		}

		Vector direction;
		if (GeneralMethods.getTargetedEntity(player, 30, new ArrayList<>()) != null) {
			direction = GeneralMethods.getDirection(source.getLocation(), GeneralMethods.getTargetedEntity(player, 30, new ArrayList<>()).getLocation());
		} else {
			direction = GeneralMethods.getDirection(source.getLocation(), GeneralMethods.getTargetedLocation(player, 30));
		}

		Item ii = player.getWorld().dropItemNaturally(source.getLocation().getBlock().getRelative(GeneralMethods.getCardinalDirection(direction)).getLocation(), is);
		ii.setPickupDelay(Integer.MAX_VALUE);
		ii.setVelocity(direction.normalize().multiply(velocity));
		playMetalbendingSound(ii.getLocation());
		thrownFragments.add(ii);

		if (counters.containsKey(source)) {
			int count = counters.get(source);
			count++;

			if (count >= maxFragments) {
				TempBlock tempBlock = findTrackedSource(source);
				if (tempBlock != null) {
					dropSource(tempBlock);
					tblockTracker.remove(tempBlock);
				} else {
					counters.remove(source);
					sources.remove(source);
				}
				source.getWorld().playSound(source.getLocation(), Sound.ENTITY_ITEM_BREAK, 10, 5);
			} else {
				counters.put(source, count);
			}

			if (sources.size() == 0) {
				remove();
			}
		}
	}

	public static void selectAnotherSource(Player player) {
		if (hasAbility(player, MetalFragments.class)) {
			getAbility(player, MetalFragments.class).selectAnotherSource();
		}
	}

	private void selectAnotherSource() {
		if (!hasSourceCapacity())
			return;

		if (prepare()) {
			Block b = selectSource();
			translateUpward(b);
		}
	}

	public boolean prepare() {
		Block block = BlockSource.getEarthSourceBlock(player, selectRange, ClickType.SHIFT_DOWN);

		if (block == null)
			return false;

		if (!isMetal(block))
			return false;

		return true;
	}

	public Block selectSource() {
		Block block = BlockSource.getEarthSourceBlock(player, selectRange, ClickType.SHIFT_DOWN);
		if (isMetal(block)) {
			return block;
		}
		return null;
	}

	public void translateUpward(Block block) {
		if (block == null)
			return;

		if (!hasSourceCapacity())
			return;

		if (sources.contains(block))
			return;

		if (block.getRelative(BlockFace.UP).getType().isSolid())
			return;

		if (isEarthbendable(player, block)) {
			BlockData sourceData = block.getBlockData().clone();
			TempFallingBlock fallingBlock = null;
			TempBlock sourceOrigin = null;
			try {
				fallingBlock = new TempFallingBlock(block.getLocation().add(0.5, 0, 0.5), sourceData, new Vector(0, 0.8, 0), this);
				sourceOrigin = JCMethods.createTempBlock(block, Material.AIR.createBlockData());
				pendingSources.put(fallingBlock, new SourceReservation(sourceOrigin));
			} catch (RuntimeException | Error exception) {
				if (fallingBlock != null) {
					fallingBlock.remove();
				}
				if (sourceOrigin != null) {
					sourceOrigin.revertBlock();
				}
				throw exception;
			}

			playMetalbendingSound(block.getLocation());
		}
	}

	private boolean hasSourceCapacity() {
		cleanupFinishedPendingSources();
		return tblockTracker.size() + pendingSources.size() < maxSources;
	}

	public void progress() {
		if (player == null || player.isDead() || !player.isOnline()) {
			remove();
			return;
		}
		if (!hasAbility(player, MetalFragments.class)) {
			return;
		}
		if (!bPlayer.canBendIgnoreCooldowns(this)) {
			remove();
			return;
		}

		Iterator<TempBlock> itr = tblockTracker.iterator();
		while (itr.hasNext()) {
			TempBlock tb = itr.next();
			if (player.getLocation().distance(tb.getLocation()) >= 10) {
				dropSource(tb);
				itr.remove();
			}
		}

		cleanupFinishedPendingSources();
		Iterator<TempFallingBlock> pendingIterator = pendingSources.keySet().iterator();
		while (pendingIterator.hasNext()) {
			TempFallingBlock tfb = pendingIterator.next();
			SourceReservation reservation = pendingSources.get(tfb);
			FallingBlock fb = tfb.getFallingBlock();
			if (fb.isOnGround()) {
				pendingIterator.remove();
				tfb.remove();
				reservation.restoreSource();
				continue;
			}
			if (fb.getLocation().getY() >= player.getEyeLocation().getY() + 1) {
				Block block = fb.getLocation().getBlock();
				try {
					TempBlock tb = JCMethods.createTempBlock(block, fb.getBlockData());
					pendingIterator.remove();
					sourceReservations.put(tb, reservation);
					tblockTracker.add(tb);
					sources.add(tb.getBlock());
					counters.put(tb.getBlock(), 0);
					tfb.remove();
				} catch (RuntimeException | Error exception) {
					pendingIterator.remove();
					tfb.remove();
					reservation.restoreSource();
					throw exception;
				}
			}
		}
		for (ListIterator<Item> iterator = thrownFragments.listIterator(); iterator.hasNext();) {
			Item f = iterator.next();

			boolean touchedLiving = false;
			for (Entity e : GeneralMethods.getEntitiesAroundPoint(f.getLocation(), 1)) {
				if (e instanceof LivingEntity && e.getEntityId() != player.getEntityId()) {
					touchedLiving = true;
					DamageHandler.damageEntity(e, damage, this);
				}
			}
			if (touchedLiving || f.isOnGround() || f.isDead()) {
				iterator.remove();
				removeFragment(f);
			}
		}

		//removeDeadFBlocks();
	}

	/*
	public void removeDeadFBlocks() {
		for (int i = 0; i < fblockTracker.size(); i++)
			if (fblockTracker.get(i).isDead())
				fblockTracker.remove(i);
	}
	*/

	public void removeDeadItems() {
		thrownFragments.removeIf(Item::isDead);
	}


	public void dropSources() {
		for (TempBlock tb : tblockTracker) {
			dropSource(tb);
		}

		tblockTracker.clear();
		cancelPendingSources();
	}

	private TempBlock findTrackedSource(Block source) {
		for (TempBlock tempBlock : tblockTracker) {
			if (tempBlock.getBlock().equals(source)) {
				return tempBlock;
			}
		}
		return null;
	}

	private void dropSource(TempBlock tempBlock) {
		Block source = tempBlock.getBlock();
		SourceReservation reservation = sourceReservations.remove(tempBlock);
		try {
			tempBlock.revertBlock();
		} finally {
			if (reservation != null) {
				reservation.restoreSource();
			}
			sources.remove(source);
			counters.remove(source);
		}
	}

	private void cleanupFinishedPendingSources() {
		Iterator<TempFallingBlock> iterator = pendingSources.keySet().iterator();
		while (iterator.hasNext()) {
			TempFallingBlock tempFallingBlock = iterator.next();
			FallingBlock fallingBlock = tempFallingBlock.getFallingBlock();
			if (!fallingBlock.isDead() && TempFallingBlock.isTempFallingBlock(fallingBlock)) {
				continue;
			}
			SourceReservation reservation = pendingSources.get(tempFallingBlock);
			iterator.remove();
			reservation.restoreSource();
		}
	}

	private void cancelPendingSources() {
		for (TempFallingBlock tempFallingBlock : pendingSources.keySet()) {
			tempFallingBlock.remove();
			pendingSources.get(tempFallingBlock).restoreSource();
		}
		pendingSources.clear();
	}

	public void removeFragments() {
		List<FragmentParticle> particles = new ArrayList<>(thrownFragments.size());
		for (Item fragment : thrownFragments) {
			particles.add(new FragmentParticle(fragment.getLocation(), fragment.getItemStack().clone()));
			fragment.remove();
		}
		thrownFragments.clear();
		particles.forEach(FragmentParticle::spawn);
	}

	private void removeFragment(Item fragment) {
		FragmentParticle particle = new FragmentParticle(fragment.getLocation(), fragment.getItemStack().clone());
		fragment.remove();
		particle.spawn();
	}

	public static void remove(Player player, Block block) {
		if (hasAbility(player, MetalFragments.class)) {
			MetalFragments mf = getAbility(player, MetalFragments.class);
			if (mf.sources.contains(block)) {
				mf.remove();
			}
		}
	}

	@Override
	public void remove() {
		try {
			dropSources();
			removeFragments();
			removeDeadItems();
			if (player.isOnline()) {
				bPlayer.addCooldown(this);
			}
		} finally {
			super.remove();
		}
	}

	private static final class FragmentParticle {
		private final Location location;
		private final ItemStack itemStack;

		private FragmentParticle(Location location, ItemStack itemStack) {
			this.location = location;
			this.itemStack = itemStack;
		}

		private void spawn() {
			location.getWorld().spawnParticle(
					Particle.ITEM_CRACK,
					location,
					3,
					0.3, 0.3, 0.3,
					0.2,
					itemStack
			);
		}
	}

	private static final class SourceReservation {
		private final TempBlock sourceOrigin;

		private SourceReservation(TempBlock sourceOrigin) {
			this.sourceOrigin = sourceOrigin;
		}

		private void restoreSource() {
			sourceOrigin.revertBlock();
		}
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
	public String getName() {
		return "MetalFragments";
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
		return "* JedCore Addon *\n" + config.getString("Abilities.Earth.MetalFragments.Description");
	}

	public int getMaxSources() {
		return maxSources;
	}

	public void setMaxSources(int maxSources) {
		this.maxSources = maxSources;
	}

	public int getSelectRange() {
		return selectRange;
	}

	public void setSelectRange(int selectRange) {
		this.selectRange = selectRange;
	}

	public int getMaxFragments() {
		return maxFragments;
	}

	public void setMaxFragments(int maxFragments) {
		this.maxFragments = maxFragments;
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

	public List<Block> getSources() {
		return sources;
	}

	public void setSources(List<Block> sources) {
		this.sources = sources;
	}

	public List<Item> getThrownFragments() {
		return thrownFragments;
	}

	public List<TempBlock> getTblockTracker() {
		return tblockTracker;
	}

	public HashMap<Block, Integer> getCounters() {
		return counters;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Earth.MetalFragments.Enabled");
	}
}
