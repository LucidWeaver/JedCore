package com.jedk1.jedcore.ability.avatar.elementsphere;

import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AvatarAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;

import com.projectkorra.projectkorra.util.TempFallingBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.concurrent.ThreadLocalRandom;

public class ESEarth extends AvatarAbility implements AddonAbility {

	private TempFallingBlock tfb;
	private long revertDelay;

	@Attribute(Attribute.DAMAGE)
	private double damage;
	@Attribute("Size")
	private int impactSize;
	@Attribute(Attribute.COOLDOWN)
	private long cooldown;

	public ESEarth(Player player) {
		super(player);
		if (!hasAbility(player, ElementSphere.class)) {
			return;
		}
		ElementSphere currES = getAbility(player, ElementSphere.class);
		if (currES.getEarthUses() == 0) {
			return;
		}
		if (bPlayer.isOnCooldown("ESEarth")) {
			return;
		}
		if (RegionProtection.isRegionProtected(this, player.getTargetBlock(getTransparentMaterialSet(), 40).getLocation())) {
			return;
		}
		setFields();
		start();
		if (!isRemoved()) {
			bPlayer.addCooldown("ESEarth", getCooldown());
			currES.setEarthUses(currES.getEarthUses() - 1);
			Location location = player.getEyeLocation().clone().add(player.getEyeLocation().getDirection().multiply(1));
			tfb = new TempFallingBlock(location, Material.DIRT.createBlockData(), location.getDirection().multiply(3), this);
			tfb.setOnPlace(this::explodeEarth);
		}
	}

	public void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		revertDelay = config.getLong("Abilities.Avatar.ElementSphere.Earth.ImpactRevert");
		damage = config.getDouble("Abilities.Avatar.ElementSphere.Earth.Damage");
		impactSize = config.getInt("Abilities.Avatar.ElementSphere.Earth.ImpactCraterSize");
		cooldown = config.getLong("Abilities.Avatar.ElementSphere.Earth.Cooldown");
	}

	@Override
	public void progress() {
		if (player == null || !player.isOnline()) {
			tfb.remove();
			remove();
			return;
		}
		if (tfb.getFallingBlock().isDead()) {
			remove();
			return;
		}
		if (RegionProtection.isRegionProtected(this, tfb.getLocation())){
			tfb.remove();
			remove();
			return;
		}

		EarthAbility.playEarthbendingSound(tfb.getLocation());

		for (Entity entity : GeneralMethods.getEntitiesAroundPoint(tfb.getLocation(), 2.5)) {
			if (entity instanceof LivingEntity && !(entity instanceof ArmorStand) && entity.getEntityId() != player.getEntityId() && !RegionProtection.isRegionProtected(this, entity.getLocation()) && !((entity instanceof Player targetPlayer) && Commands.invincible.contains(targetPlayer.getName()))) {
				DamageHandler.damageEntity(entity, damage, this);
			}
		}
	}

	private void explodeEarth(TempFallingBlock tempFallingBlock) {
		Location impact = tempFallingBlock.getLocation();

		impact.getWorld().spawnParticle(Particle.SMOKE_LARGE, impact, 25, 0, 0, 0, 0.3);
		impact.getWorld().playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.5f);

		ThreadLocalRandom rand = ThreadLocalRandom.current();
		long minRevert = Math.max(0, revertDelay - 1000);

		for (Location l : GeneralMethods.getCircle(impact, impactSize, 1, false, true, 0)) {
			if (JCMethods.isUnbreakable(l.getBlock()) || RegionProtection.isRegionProtected(this, l)) {
				continue;
			}

			if (EarthAbility.isEarthbendable(player, l.getBlock())) {
				impact.getWorld().spawnParticle(Particle.SMOKE_LARGE, l, 2, 0, 0, 0, 0.1);
				new RegenTempBlock(l.getBlock(), Material.AIR, Material.AIR.createBlockData(), minRevert + rand.nextInt(1000), false);
			} else if (ElementalAbility.isAir(l.getBlock().getType()) && rand.nextInt(20) == 0
					&& EarthAbility.isEarthbendable(player, l.getBlock().getRelative(BlockFace.DOWN))) {
				Material type = l.getBlock().getRelative(BlockFace.DOWN).getType();
				new RegenTempBlock(l.getBlock(), type, type.createBlockData(), minRevert + rand.nextInt(1000));
			}
		}
	}

	@Override
	public long getCooldown() {
		return cooldown;
	}

	@Override
	public Location getLocation() {
		return tfb != null ? tfb.getLocation() : null;
	}

	@Override
	public String getName() {
		return "ElementSphereEarth";
	}
	
	@Override
	public boolean isHiddenAbility() {
		return true;
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
		return null;
	}

	public long getRevertDelay() {
		return revertDelay;
	}

	public void setRevertDelay(long revertDelay) {
		this.revertDelay = revertDelay;
	}

	public double getDamage() {
		return damage;
	}

	public void setDamage(double damage) {
		this.damage = damage;
	}

	public int getImpactSize() {
		return impactSize;
	}

	public void setImpactSize(int impactSize) {
		this.impactSize = impactSize;
	}

	public TempFallingBlock getTempFallingBlock() {
		return tfb;
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Avatar.ElementSphere.Enabled");
	}
}
