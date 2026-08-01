package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.collision.AABB;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.collision.CollisionUtil;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.BlockUtil;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.AttributeCache;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.event.AbilityRecalculateAttributeEvent;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;

import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.stream.Collectors.toList;

public class EarthKick extends EarthAbility implements AddonAbility {
	private static final String METAL_DAMAGE_ATTRIBUTE = "Metal" + Attribute.DAMAGE;

	private final List<TempFallingBlock> temps = new ArrayList<>();
	private final Set<UUID> hitEntities = new HashSet<>();

	private BlockData materialData;
	private Location location;
	private Block block;
	private boolean multipleHits;
	private int sourceRange;
	private int spread;
	private double velocity;
	private boolean allowMetal;
	private boolean replaceSource;

	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute("MaxShots")
	private int earthBlocks;
	@Attribute(Attribute.DAMAGE)
	private double damage;
	@Attribute(METAL_DAMAGE_ATTRIBUTE)
	private double metalDmg;
	@Attribute("CollisionRadius")
	private double entityCollisionRadius;

	public EarthKick(Player player) {
		super(player);

		if (!bPlayer.canBend(this)) {
			return;
		}

		setFields();

		location = player.getLocation();

		if ((player.getLocation().getPitch() > -5) && prepare()) {
			start();
			if (!isStarted()) {
				return;
			}

			if (!prepareSource()) {
				remove();
				return;
			}

			revertSource();
			launchBlocks();
		}
	}

	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		
		cooldown = config.getLong("Abilities.Earth.EarthKick.Cooldown");
		earthBlocks = config.getInt("Abilities.Earth.EarthKick.EarthBlocks");
		damage = config.getDouble("Abilities.Earth.EarthKick.Damage.Normal");
		metalDmg = config.getDouble("Abilities.Earth.EarthKick.Damage.Metal");
		entityCollisionRadius = config.getDouble("Abilities.Earth.EarthKick.EntityCollisionRadius");
		multipleHits = config.getBoolean("Abilities.Earth.EarthKick.MultipleHits");
		sourceRange = config.getInt("Abilities.Earth.EarthKick.SourceRange");
		spread = config.getInt("Abilities.Earth.EarthKick.Spread");
		velocity = config.getDouble("Abilities.Earth.EarthKick.Velocity");
		allowMetal = config.getBoolean("Abilities.Earth.EarthKick.AllowMetal");
		replaceSource = config.getBoolean("Abilities.Earth.EarthKick.ReplaceSource");

		if (entityCollisionRadius < 1.0) {
			entityCollisionRadius = 1.0;
		}
	}

	private boolean prepare() {
		block = player.getTargetBlock(getTransparentMaterialSet(), sourceRange);
		return prepareSource();
	}

	private boolean prepareSource() {
		if (block == null) {
			return false;
		}

		if (!isEarthbendable(player, block) || RegionProtection.isRegionProtected(this, block.getLocation())) {
			return false;
		}

		BlockData sourceData = getRevertedSourceData();
		if (!canUseSource(sourceData.getMaterial())) {
			return false;
		}

		materialData = sourceData;
		location.setX(block.getX() + 0.5);
		location.setY(block.getY());
		location.setZ(block.getZ() + 0.5);

		return true;
	}

	private boolean canUseSource(Material material) {
		if (!EarthAbility.isEarthbendable(material, true, true, true)) {
			return false;
		}
		if (isMetal(material)) {
			return allowMetal && bPlayer.canMetalbend();
		}
		if (isSand(material)) {
			return bPlayer.canSandbend();
		}
		return !isLava(material) || bPlayer.canLavabend();
	}

	private BlockData getRevertedSourceData() {
		if (!TempBlock.isTempBlock(block)) {
			return block.getBlockData().clone();
		}

		List<TempBlock> tempBlocks = TempBlock.getAll(block);
		int sourceIndex = tempBlocks.size() - 2;
		if (sourceIndex >= 0 && DensityShift.getSandBlocks().contains(tempBlocks.get(sourceIndex))) {
			sourceIndex--;
		}
		if (sourceIndex >= 0) {
			return tempBlocks.get(sourceIndex).getBlockData().clone();
		}
		return tempBlocks.get(0).getState().getBlockData().clone();
	}

	private void revertSource() {
		if (TempBlock.isTempBlock(block)) {
			TempBlock.get(block).revertBlock();
		}
		if (DensityShift.isPassiveSand(block)) {
			DensityShift.revertSand(block);
		}
	}

	@Override
	public void progress() {
		if (player.isDead() || !player.isOnline()) {
			remove();
			return;
		}

		if (!bPlayer.canBendIgnoreBindsCooldowns(this)) {
			remove();
			return;
		}

		bPlayer.addCooldown(this);

		track();

		if (temps.isEmpty()) {
			remove();
		}
	}

	private void launchBlocks() {
		if (replaceSource) {
			if (block.getType() != Material.AIR) {
				TempBlock air = JCMethods.createTempBlock(block, Material.AIR);
				air.setRevertTime(5000L);
			}
		}

		location.setPitch(0);
		location.add(location.getDirection());

		if (!isAir(location.getBlock().getType())) {
			location.setY(location.getY() + 1.0);
		}

		location.getWorld().spawnParticle(Particle.CRIT, location, 10, Math.random(), Math.random(), Math.random(), 0.1);
		int yaw = Math.round(location.getYaw());

		playEarthbendingSound(location);

		ThreadLocalRandom rand = ThreadLocalRandom.current();

		for (int i = 0; i < earthBlocks; i++) {
			location.setYaw(yaw + rand.nextInt((spread * 2) + 1) - spread);
			location.setPitch(rand.nextInt(25) - 45);

			Vector dir = location.getDirection().multiply(velocity);

			temps.add(new TempFallingBlock(location, materialData, dir, this));
		}
	}

	public void track() {
		List<TempFallingBlock> destroy = new ArrayList<>();

		for (TempFallingBlock tfb : temps) {
			FallingBlock fb = tfb.getFallingBlock();

			if (fb == null || fb.isDead()) {
				destroy.add(tfb);
				continue;
			}

			Location particleLocation = fb.getLocation();
			for (int i = 0; i < 2; i++) {
				particleLocation.getWorld().spawnParticle(Particle.BLOCK_CRACK, particleLocation, 1, 0.0, 0.0, 0.0, 0.1, materialData);
				particleLocation.getWorld().spawnParticle(Particle.BLOCK_CRACK, particleLocation, 1, 0.0, 0.0, 0.0, 0.2, materialData);
			}

			AABB collider = BlockUtil.getFallingBlockBoundsFull(fb).scale(entityCollisionRadius * 2.0);

			CollisionDetector.checkEntityCollisions(player, fb.getWorld(), collider, (entity) -> {
				if (RegionProtection.isRegionProtected(this, entity.getLocation())) {
					return false;
				}

				UUID uuid = entity.getUniqueId();
				if (this.multipleHits || hitEntities.add(uuid)) {
					DamageHandler.damageEntity(entity, isMetal(fb.getBlockData().getMaterial()) ? metalDmg : damage, this);
				}
				return false;
			});
		}

		temps.removeAll(destroy);
	}

	public static void applyAvatarStateModifier(AbilityRecalculateAttributeEvent event) {
		EarthKick ability = (EarthKick) event.getAbility();

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

	@Override
	public long getCooldown() {
		return cooldown;
	}

	@Override
	public Location getLocation() {
		return location;
	}

	@Override
	public List<Location> getLocations() {
		return temps.stream().map(TempFallingBlock::getLocation).collect(toList());
	}

	@Override
	public double getCollisionRadius() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getDouble("Abilities.Earth.EarthKick.AbilityCollisionRadius");
	}

	@Override
	public void handleCollision(Collision collision) {
		CollisionUtil.handleFallingBlockCollisions(collision, temps);
	}

	@Override
	public String getName() {
		return "EarthKick";
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
		return "* JedCore Addon *\n" + config.getString("Abilities.Earth.EarthKick.Description");
	}

	public List<TempFallingBlock> getTemps() {
		return temps;
	}

	public BlockData getMaterialData() {
		return materialData;
	}

	public void setMaterialData(BlockData materialData) {
		this.materialData = materialData;
	}

	public void setLocation(Location location) {
		this.location = location;
	}

	public int getEarthBlocksQuantity() {
		return earthBlocks;
	}

	public void setEarthBlocksQuantity(int earthBlocks) {
		this.earthBlocks = earthBlocks;
	}

	public double getDamage() {
		return damage;
	}

	public void setDamage(double damage) {
		this.damage = damage;
	}

	public double getMetalDmg() {
		return metalDmg;
	}

	public void setMetalDmg(double metalDmg) {
		this.metalDmg = metalDmg;
	}

	public double getEntityCollisionRadius() {
		return entityCollisionRadius;
	}

	public void setEntityCollisionRadius(double entityCollisionRadius) {
		this.entityCollisionRadius = entityCollisionRadius;
	}

	public Block getBlock() {
		return block;
	}

	public void setBlock(Block block) {
		this.block = block;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Earth.EarthKick.Enabled");
	}
}
