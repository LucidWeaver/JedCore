package com.jedk1.jedcore.util;

import com.jedk1.jedcore.JedCore;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.util.DamageHandler;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ThrownEntityTracker {

	public static ConcurrentHashMap<Entity, ThrownEntityTracker> instances = new ConcurrentHashMap<>();
	public static boolean collisions = JedCore.plugin.getConfig().getBoolean("Properties.MobCollisions.Enabled");
	private final long delay;
	private final long fireTime;
	private final Entity entity;
	private final Player instigator;
	private final Ability ability;

	public ThrownEntityTracker(Ability ability, Entity e, Player instigator, long delay) {
		entity = e;
		this.instigator = instigator;
		this.ability = ability;
		fireTime = System.currentTimeMillis();
		this.delay = delay;
		instances.put(entity, this);
	}

	public void update() {
		if (System.currentTimeMillis() < fireTime + delay) {
			return;
		}

		if (!collisions || entity.isOnGround()) {
			remove();
			return;
		}

		Vector velocity = entity.getVelocity().clone();

		List<Entity> nearby = GeneralMethods.getEntitiesAroundPoint(entity.getLocation(), 2D);
		nearby.remove(entity);
		nearby.remove(instigator);

		if (!nearby.isEmpty()) {
			entity.setVelocity(velocity.multiply(0.5D));
			for (Entity e : nearby) {
				e.setVelocity(entity.getVelocity().multiply(0.25D).add(GeneralMethods.getDirection(entity.getLocation(), e.getLocation()).multiply(2)));
				if (e instanceof LivingEntity) {
					DamageHandler.damageEntity(e, 2D, ability);
				}
				if (entity instanceof LivingEntity) {
					DamageHandler.damageEntity(entity, 1D, ability);
					((LivingEntity) entity).setNoDamageTicks(0);
				}
			}
			remove();
		}
	}

	public static void updateAll() {
		for (ThrownEntityTracker tracker : instances.values()) {
			tracker.update();
		}
	}

	public static void reload() {
		collisions = JedCore.plugin.getConfig().getBoolean("Properties.MobCollisions.Enabled");
	}

	public void remove() {
		remove(entity);
	}

	public static void remove(Entity entity) {
		instances.remove(entity);
	}
	
	public static void removeAll() {
		instances.clear();
	}

}
