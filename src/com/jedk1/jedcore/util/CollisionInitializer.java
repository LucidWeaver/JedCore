package com.jedk1.jedcore.util;

import com.jedk1.jedcore.JedCore;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CollisionInitializer<T extends CoreAbility> {
    // This is used for special mappings where collisions are stored in a separate place.
    public static Map<String, String> abilityMap = new HashMap<>();
    private final Class<T> type;
    private final EnumSet<CollisionGroup> groups = EnumSet.noneOf(CollisionGroup.class);
    private boolean prepared;
    private boolean active;
    private boolean primaryGroupsInitialized;
    private boolean secondaryGroupsInitialized;
    private boolean explicitCollisionsInitialized;
    private CoreAbility ability;
    private String abilityName;
    private ConfigurationSection section;

    public CollisionInitializer(Class<T> type) {
        this.type = type;
    }

    public boolean initialize() {
        if (!prepare()) return false;

        initializePrimaryGroups();
        initializeSecondaryGroups();
        initializeExplicitCollisions();

        return true;
    }

    public boolean initializePrimaryGroups() {
        if (!prepare()) return false;
        if (primaryGroupsInitialized) return true;

        if (groups.contains(CollisionGroup.SMALL)) initializeGroup(CollisionGroup.SMALL);
        if (groups.contains(CollisionGroup.LARGE)) initializeGroup(CollisionGroup.LARGE);

        primaryGroupsInitialized = true;
        return true;
    }

    public boolean initializeSecondaryGroups() {
        if (!prepare()) return false;
        if (secondaryGroupsInitialized) return true;

        if (groups.contains(CollisionGroup.COMBO)) initializeGroup(CollisionGroup.COMBO);
        if (groups.contains(CollisionGroup.REMOVE_SPOUT)) initializeGroup(CollisionGroup.REMOVE_SPOUT);
        if (groups.contains(CollisionGroup.IGNORE)) initializeGroup(CollisionGroup.IGNORE);

        secondaryGroupsInitialized = true;
        return true;
    }

    public boolean initializeExplicitCollisions() {
        if (!prepare()) return false;
        if (explicitCollisionsInitialized) return true;

        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase("Enabled") || key.equalsIgnoreCase("Group")) continue;

            ConfigurationSection abilityConfig = section.getConfigurationSection(key);
            if (abilityConfig == null) continue;

            boolean enabled = abilityConfig.getBoolean("Enabled");
            if (!enabled) continue;

            boolean removeFirst = abilityConfig.getBoolean("RemoveFirst");
            boolean removeSecond = abilityConfig.getBoolean("RemoveSecond");

            CoreAbility secondAbility = AbilitySelector.getAbility(key);

            if (secondAbility != null) {
                JedCore.logDebug("Initializing collision for " + abilityName + " => " + key);

                ProjectKorra.getCollisionManager().addCollision(new Collision(ability, secondAbility, removeFirst, removeSecond));
            }
        }

        explicitCollisionsInitialized = true;
        return true;
    }

    private boolean prepare() {
        if (prepared) return active;
        prepared = true;

        ability = CoreAbility.getAbility(type);
        if (ability == null) return false;

        abilityName = ability.getName();

        Element element = ability.getElement();
        if (element instanceof Element.SubElement) {
            element = ((Element.SubElement) element).getParentElement();
            if (element == null) {
                element = ability.getElement();
            }
        }

        if (abilityMap.containsKey(abilityName)) {
            abilityName = abilityMap.get(abilityName);
        }

        String collisionPath = getCollisionPath(abilityName, element);

        section = JedCore.plugin.getConfig().getConfigurationSection(collisionPath);
        if (section == null) return false;
        if (section.contains("Enabled") && !section.getBoolean("Enabled")) return false;

        for (String groupName : getConfiguredGroups()) {
            if (groupName == null || groupName.isBlank()) continue;

            CollisionGroup group = CollisionGroup.fromConfig(groupName);
            if (group == null) {
                JedCore.log.warning("Unknown collision group '" + groupName + "' for " + abilityName
                        + ". Supported groups: Small, Large, Combo, RemoveSpout, Ignore.");
                continue;
            }
            groups.add(group);
        }

        active = true;
        return true;
    }

    private List<String> getConfiguredGroups() {
        if (section.isList("Group")) {
            return section.getStringList("Group");
        }

        String group = section.getString("Group");
        return group == null ? List.of() : List.of(group);
    }

    private void initializeGroup(CollisionGroup group) {
        switch (group) {
            case SMALL:
                JedCore.logDebug("Initializing small collision for " + abilityName + ".");
                ProjectKorra.getCollisionInitializer().addSmallAbility(ability);
                break;
            case LARGE:
                JedCore.logDebug("Initializing large collision for " + abilityName + ".");
                ProjectKorra.getCollisionInitializer().addLargeAbility(ability);
                break;
            case COMBO:
                JedCore.logDebug("Initializing combo collision for " + abilityName + ".");
                ProjectKorra.getCollisionInitializer().addComboAbility(ability);
                break;
            case REMOVE_SPOUT:
                JedCore.logDebug("Initializing remove-spout collision for " + abilityName + ".");
                ProjectKorra.getCollisionInitializer().addRemoveSpoutAbility(ability);
                break;
            case IGNORE:
                JedCore.logDebug("Initializing ignore collision for " + abilityName + ".");
                ProjectKorra.getCollisionInitializer().addIgnoreAbility(ability);
                break;
        }
    }

    private String getCollisionPath(String abilityName, Element element) {
        CoreAbility ability = CoreAbility.getAbility(abilityName);
        if (ability == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        sb.append("Abilities.").append(element.getName()).append(".");

        if (ability instanceof ComboAbility) {
            sb.append(element.getName()).append("Combo.");
        }

        sb.append(abilityName).append(".Collisions");

        return sb.toString();
    }

    private enum CollisionGroup {
        SMALL("Small"),
        LARGE("Large"),
        COMBO("Combo"),
        REMOVE_SPOUT("RemoveSpout"),
        IGNORE("Ignore");

        private final String configName;

        CollisionGroup(String configName) {
            this.configName = configName;
        }

        private static CollisionGroup fromConfig(String value) {
            for (CollisionGroup group : values()) {
                if (group.configName.equalsIgnoreCase(value.trim())) {
                    return group;
                }
            }
            return null;
        }
    }
}
