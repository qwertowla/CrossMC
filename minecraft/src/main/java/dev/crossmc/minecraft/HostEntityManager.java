package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.EntityMap;
import dev.crossmc.bridge.Protocol;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Host entity &lt;-&gt; Minecraft proxy entity binding (logical server side).
 *
 * <p>Host creatures/bosses/items are represented by hidden, gravity-less armor-stand proxies
 * spawned on the integrated server, so Minecraft's <b>native</b> damage/explosion logic can act
 * on them (see {@link DamageBridge}). Each proxy carries {@code crossmc_proxy} and
 * {@code crossmc_id_<hostEntityId>} tags, giving a stable mapping both ways.
 *
 * <p>A proxy is only spawned on the logical server (singleplayer/LAN integrated server); on a
 * remote dedicated server the client cannot create server entities, which is a documented
 * limitation of this phase.
 */
public final class HostEntityManager {
	private static final Map<Integer, ArmorStandEntity> PROXIES = new HashMap<>();
	private static final Set<Integer> SEEN = new HashSet<>();

	private HostEntityManager() {
	}

	public static void tick(MinecraftServer server) {
		BridgeMemory memory = CrossMcMinecraftClient.memory();

		if (memory == null) {
			return;
		}

		if (!memory.hostAlive(System.currentTimeMillis())) {
			if (!PROXIES.isEmpty()) {
				for (ArmorStandEntity stand : PROXIES.values()) {
					stand.discard();
				}

				PROXIES.clear();
				CrossMcMinecraft.LOGGER.info("CrossMC: host disconnected — proxy entities removed");
			}

			return;
		}

		EntityMap[] rows;

		try {
			rows = memory.readEntityTable();
		} catch (RuntimeException e) {
			return;
		}

		ServerWorld world = server.getOverworld();

		if (world == null) {
			return;
		}

		SEEN.clear();

		for (EntityMap row : rows) {
			if ((row.flags & Protocol.ENTITY_DEAD) != 0 || row.hostEntityId == 0) {
				continue;
			}

			if (row.kind != Protocol.ENTITY_CREATURE
					&& row.kind != Protocol.ENTITY_BOSS
					&& row.kind != Protocol.ENTITY_ITEM) {
				continue;
			}

			SEEN.add(row.hostEntityId);
			ArmorStandEntity stand = PROXIES.get(row.hostEntityId);

			if (stand == null || stand.isRemoved()) {
				stand = spawn(world, row);
				PROXIES.put(row.hostEntityId, stand);
			} else {
				stand.refreshPositionAndAngles(row.x, row.y, row.z, row.yaw, row.pitch);
			}
		}

		Iterator<Map.Entry<Integer, ArmorStandEntity>> it = PROXIES.entrySet().iterator();

		while (it.hasNext()) {
			Map.Entry<Integer, ArmorStandEntity> entry = it.next();

			if (!SEEN.contains(entry.getKey())) {
				entry.getValue().discard();
				it.remove();
			}
		}
	}

	private static ArmorStandEntity spawn(ServerWorld world, EntityMap row) {
		ArmorStandEntity stand = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
		stand.refreshPositionAndAngles(row.x, row.y, row.z, row.yaw, row.pitch);
		stand.setInvisible(true);
		stand.setNoGravity(true);
		stand.setSilent(true);
		stand.setInvulnerable(false);
		stand.setCustomNameVisible(false);
		stand.addCommandTag(DamageBridge.TAG_MARKER);

		if (row.crossEntityId != 0) {
			stand.addCommandTag(DamageBridge.TAG_CROSS_PREFIX + row.crossEntityId);
		}

		world.spawnEntity(stand);
		CrossMcMinecraft.LOGGER.info("CrossMC: spawned proxy entity for CrossEntityId {} (host {}) at ({}, {}, {})",
				row.crossEntityId, row.hostEntityId, row.x, row.y, row.z);
		return stand;
	}

	public static int proxyCount() {
		return PROXIES.size();
	}
}
