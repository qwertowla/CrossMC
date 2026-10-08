package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.DamageEvent;
import dev.crossmc.bridge.Protocol;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.registry.tag.DamageTypeTags;

/**
 * Minecraft -> host damage events.
 *
 * <p>Rather than writing a special TNT system, this observes Minecraft's <b>native</b> damage
 * events (via {@code ServerLivingEntityEvents.AFTER_DAMAGE}): melee, arrows/projectiles,
 * explosions/TNT, fall, fire, and anything other mods add, all flow through the same path. Any
 * event landing on a proxy entity (identified by command tags) is forwarded to the host, which
 * decides the real effect and its own multipliers.
 */
public final class DamageBridge {
	/** Marker tag placed on every proxy entity. */
	public static final String TAG_MARKER = "crossmc_proxy";
	/** Tag carrying the host entity id: {@code crossmc_id_<n>}. */
	public static final String TAG_ID_PREFIX = "crossmc_id_";

	private DamageBridge() {
	}

	public static void onDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
		if (!entity.getCommandTags().contains(TAG_MARKER)) {
			return;
		}

		int hostId = hostIdOf(entity);

		if (hostId == 0) {
			return;
		}

		BridgeMemory memory = CrossMcMinecraftClient.memory();

		if (memory == null) {
			return;
		}

		DamageEvent event = new DamageEvent();
		event.hostEntityId = hostId;
		event.mcEntityId = entity.getId();
		event.sourceType = classify(source);
		event.flags = 0;
		event.amount = damageTaken;
		event.attackerHostId = 0;
		event.x = (float) entity.getX();
		event.y = (float) entity.getY();
		event.z = (float) entity.getZ();
		event.knockbackX = 0f;
		event.knockbackZ = 0f;
		event.timestampMs = System.currentTimeMillis();
		memory.pushDamage(event);

		CrossMcMinecraft.LOGGER.info("CrossMC: damage on host entity {} (mc {}) type={} amount={}",
				hostId, entity.getId(), event.sourceType, damageTaken);
	}

	/** Parses the host entity id from an entity's command tags, or 0. */
	public static int hostIdOf(Entity entity) {
		for (String tag : entity.getCommandTags()) {
			if (tag.startsWith(TAG_ID_PREFIX)) {
				try {
					return Integer.parseInt(tag.substring(TAG_ID_PREFIX.length()));
				} catch (NumberFormatException ignored) {
					// fall through
				}
			}
		}

		return 0;
	}

	private static int classify(DamageSource source) {
		Entity attacker = source.getAttacker();

		if (attacker instanceof PlayerEntity) {
			return Protocol.DMG_PLAYER;
		}

		if (attacker instanceof PersistentProjectileEntity) {
			return Protocol.DMG_PROJECTILE;
		}

		if (attacker instanceof LivingEntity) {
			return Protocol.DMG_MOB;
		}

		if (source.isIn(DamageTypeTags.IS_EXPLOSION)) {
			return Protocol.DMG_EXPLOSION;
		}

		if (source.isIn(DamageTypeTags.IS_FALL)) {
			return Protocol.DMG_FALL;
		}

		if (source.isIn(DamageTypeTags.IS_FIRE)) {
			return Protocol.DMG_FIRE;
		}

		if (source.isIn(DamageTypeTags.IS_PROJECTILE)) {
			return Protocol.DMG_PROJECTILE;
		}

		return Protocol.DMG_GENERIC;
	}
}
