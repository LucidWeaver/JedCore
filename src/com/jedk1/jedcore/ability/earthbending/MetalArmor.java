package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.earthbending.EarthArmor;
import com.projectkorra.projectkorra.util.TempArmor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MetalArmor extends EarthAbility implements AddonAbility {

	private boolean useMetalArmor;
	private boolean resistanceEnabled;
	private boolean resistanceDurationEnabled;
	private int resistStrength;
	private int resistDuration;
	private boolean armorApplied;
	private boolean resistanceApplied;
	private long resistanceEndTime;
	private long previousResistanceStartTick;
	private EarthArmor earthArmor;
	private Map<ArmorType, List<String>> armorMaterials;
	private PotionEffect previousResistance;
	private PotionEffect resistance;

	public MetalArmor(Player player) {
		super(player);
		initialize(player == null ? null : CoreAbility.getAbility(player, EarthArmor.class));
	}

	public MetalArmor(Player player, EarthArmor earthArmor) {
		super(player);
		initialize(earthArmor);
	}

	private void initialize(EarthArmor earthArmor) {
		if (bPlayer == null || earthArmor == null || earthArmor.isRemoved() || !bPlayer.canMetalbend()) {
			return;
		}

		if (CoreAbility.getAbility(player, EarthArmor.class) != earthArmor || CoreAbility.hasAbility(player, MetalArmor.class)) {
			return;
		}

		this.earthArmor = earthArmor;
		setFields();
		start();
	}

	private void setFields() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		String path = "Abilities.Earth.EarthArmor.UseMetalArmor";

		useMetalArmor = config.getBoolean(path + ".Enabled");
		resistanceEnabled = config.getBoolean("Abilities.Earth.EarthArmor.Resistance.Enabled");
		resistanceDurationEnabled = config.getBoolean("Abilities.Earth.EarthArmor.Resistance.Duration.Enabled");
		resistStrength = config.getInt("Abilities.Earth.EarthArmor.Resistance.Strength");
		resistDuration = config.getInt("Abilities.Earth.EarthArmor.Resistance.Duration.Value");
		armorMaterials = new EnumMap<>(ArmorType.class);
		for (ArmorType armorType : ArmorType.values()) {
			armorMaterials.put(armorType, config.getStringList(path + "." + armorType.configKey));
		}
	}

	@Override
	public void progress() {
		if (player == null || !player.isOnline() || player.isDead()) {
			remove();
			return;
		}

		if (earthArmor == null || earthArmor.isRemoved() || CoreAbility.getAbility(player, EarthArmor.class) != earthArmor) {
			remove();
			return;
		}

		if (!bPlayer.isToggled()) {
			remove();
			earthArmor.remove();
			return;
		}

		if (!earthArmor.isFormed()) {
			return;
		}

		if (!armorApplied) {
			if (!EarthAbility.isMetal(earthArmor.getHeadMaterial())) {
				remove();
				return;
			}

			TempArmor tempArmor = getEarthArmorTempArmor();
			if (tempArmor == null) {
				remove();
				return;
			}

			tempArmor.setArmor(createArmor(earthArmor.getHeadMaterial()));
			armorApplied = true;
			if (resistanceEnabled && (!resistanceDurationEnabled || resistDuration > 0)) {
				applyResistance();
			}
		}

		if (!resistanceApplied) {
			return;
		}

		if (resistanceDurationEnabled && System.currentTimeMillis() >= resistanceEndTime) {
			clearResistance();
			return;
		}

		if (player.getPotionEffect(resistance.getType()) == null) {
			previousResistance = null;
			resistanceApplied = player.addPotionEffect(resistance);
		}
	}

	private void applyResistance() {
		PotionEffect template = JedCore.plugin.getPotionEffectAdapter().getResistanceEffect(50, resistStrength);
		int duration = resistanceDurationEnabled ? Math.max(1, resistDuration / 50) : PotionEffect.INFINITE_DURATION;
		resistance = new PotionEffect(
				template.getType(),
				duration,
				template.getAmplifier(),
				template.isAmbient(),
				template.hasParticles(),
				template.hasIcon()
		);
		previousResistance = player.getPotionEffect(resistance.getType());
		previousResistanceStartTick = CoreAbility.getCurrentTick();
		if (previousResistance != null) {
			player.removePotionEffect(resistance.getType());
		}
		resistanceApplied = player.addPotionEffect(resistance);
		if (!resistanceApplied) {
			restorePreviousResistance();
			previousResistance = null;
			return;
		}
		if (resistanceDurationEnabled) {
			resistanceEndTime = System.currentTimeMillis() + resistDuration;
		}
	}

	private void clearResistance() {
		if (!resistanceApplied || resistance == null || player == null) {
			return;
		}

		PotionEffect activeResistance = player.getPotionEffect(resistance.getType());
		if (activeResistance == null || isManagedResistance(activeResistance)) {
			player.removePotionEffect(resistance.getType());
			restorePreviousResistance();
		} else {
			player.removePotionEffect(resistance.getType());
			player.addPotionEffect(activeResistance);
		}

		resistanceApplied = false;
		previousResistance = null;
		resistance = null;
	}

	private boolean isManagedResistance(PotionEffect effect) {
		return effect.getAmplifier() == resistance.getAmplifier()
				&& effect.isAmbient() == resistance.isAmbient()
				&& effect.hasParticles() == resistance.hasParticles()
				&& effect.hasIcon() == resistance.hasIcon()
				&& (resistance.isInfinite() ? effect.isInfinite() : effect.getDuration() <= resistance.getDuration());
	}

	private void restorePreviousResistance() {
		if (previousResistance == null) {
			return;
		}

		if (previousResistance.isInfinite()) {
			player.addPotionEffect(previousResistance);
			return;
		}

		long elapsedTicks = Math.max(0, CoreAbility.getCurrentTick() - previousResistanceStartTick);
		long remainingTicks = previousResistance.getDuration() - elapsedTicks;
		if (remainingTicks <= 0) {
			return;
		}

		PotionEffect restoredResistance = new PotionEffect(
				previousResistance.getType(),
				(int)Math.min(Integer.MAX_VALUE, remainingTicks),
				previousResistance.getAmplifier(),
				previousResistance.isAmbient(),
				previousResistance.hasParticles(),
				previousResistance.hasIcon()
		);
		player.addPotionEffect(restoredResistance);
	}

	@SuppressWarnings("deprecation")
	public void updateGoldHearts(EntityDamageEvent event) {
		if (!armorApplied
				|| earthArmor == null
				|| earthArmor.isRemoved()
				|| event.getEntity() != player
				|| !event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)) {
			return;
		}

		double absorptionDamage = event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION);
		if (absorptionDamage < 0) {
			earthArmor.setGoldHearts(Math.max(0, player.getAbsorptionAmount() + absorptionDamage));
		}
	}

	public boolean isTracking(EarthArmor earthArmor) {
		return this.earthArmor == earthArmor;
	}

	private ItemStack[] createArmor(Material source) {
		ArmorType armorType = getArmorType(source);
		ItemStack[] armor = createArmorIfAvailable(armorType.materialPrefix);
		if (armor != null) {
			return armor;
		}

		if (armorType == ArmorType.COPPER) {
			armor = createArmorIfAvailable(ArmorType.IRON.materialPrefix);
			if (armor != null) {
				return armor;
			}
		}

		return createArmor(
				Material.CHAINMAIL_BOOTS,
				Material.CHAINMAIL_LEGGINGS,
				Material.CHAINMAIL_CHESTPLATE,
				Material.CHAINMAIL_HELMET
		);
	}

	private ArmorType getArmorType(Material source) {
		if (!useMetalArmor) {
			return ArmorType.CHAIN;
		}

		for (ArmorType armorType : ArmorType.values()) {
			if (matchesMaterial(source, armorMaterials.get(armorType))) {
				return armorType;
			}
		}

		return ArmorType.CHAIN;
	}

	private boolean matchesMaterial(Material source, List<String> entries) {
		for (String entry : entries) {
			if (entry == null) {
				continue;
			}

			String value = entry.trim();
			if (value.isEmpty()) {
				continue;
			}

			if (value.startsWith("#")) {
				NamespacedKey key = NamespacedKey.fromString(value.substring(1).toLowerCase(Locale.ROOT));
				if (key == null) {
					continue;
				}

				Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
				if (tag != null && tag.isTagged(source)) {
					return true;
				}
			} else if (source.name().equalsIgnoreCase(value)) {
				return true;
			}
		}

		return false;
	}

	private ItemStack[] createArmorIfAvailable(String materialPrefix) {
		Material bootsMaterial = Material.getMaterial(materialPrefix + "_BOOTS");
		Material leggingsMaterial = Material.getMaterial(materialPrefix + "_LEGGINGS");
		Material chestplateMaterial = Material.getMaterial(materialPrefix + "_CHESTPLATE");
		Material helmetMaterial = Material.getMaterial(materialPrefix + "_HELMET");
		if (bootsMaterial == null || leggingsMaterial == null || chestplateMaterial == null || helmetMaterial == null) {
			return null;
		}

		return createArmor(bootsMaterial, leggingsMaterial, chestplateMaterial, helmetMaterial);
	}

	private ItemStack[] createArmor(Material boots, Material leggings, Material chestplate, Material helmet) {
		return new ItemStack[] {
				new ItemStack(boots),
				new ItemStack(leggings),
				new ItemStack(chestplate),
				new ItemStack(helmet)
		};
	}

	private TempArmor getEarthArmorTempArmor() {
		for (TempArmor tempArmor : TempArmor.getTempArmorList(player)) {
			if (tempArmor.getAbility() == earthArmor) {
				return tempArmor;
			}
		}

		return null;
	}

	@Override
	public long getCooldown() {
		return 0;
	}

	@Override
	public Location getLocation() {
		return null;
	}

	@Override
	public String getName() {
		return "MetalArmor";
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
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return "* JedCore Addon *\n" + config.getString("Abilities.Earth.EarthArmor.Description");
	}

	public boolean isUseMetalArmor() {
		return useMetalArmor;
	}

	public void setUseMetalArmor(boolean useMetalArmor) {
		this.useMetalArmor = useMetalArmor;
	}

	public int getResistStrength() {
		return resistStrength;
	}

	public void setResistStrength(int resistStrength) {
		this.resistStrength = resistStrength;
	}

	public int getResistDuration() {
		return resistDuration;
	}

	public void setResistDuration(int resistDuration) {
		this.resistDuration = resistDuration;
	}

	@Override
	public void remove() {
		if (isRemoved()) {
			return;
		}

		clearResistance();
		super.remove();
	}

	@Override
	public void load() {}

	@Override
	public void stop() {}

	@Override
	public boolean isEnabled() {
		ConfigurationSection config = JedCoreConfig.getConfig(this.player);
		return config.getBoolean("Abilities.Earth.EarthArmor.Enabled");
	}

	private enum ArmorType {
		COPPER("CopperArmor", "COPPER"),
		IRON("IronArmor", "IRON"),
		GOLD("GoldArmor", "GOLDEN"),
		NETHERITE("NetheriteArmor", "NETHERITE"),
		DIAMOND("DiamondArmor", "DIAMOND"),
		CHAIN("ChainArmor", "CHAINMAIL");

		private final String configKey;
		private final String materialPrefix;

		ArmorType(String configKey, String materialPrefix) {
			this.configKey = configKey;
			this.materialPrefix = materialPrefix;
		}
	}
}
