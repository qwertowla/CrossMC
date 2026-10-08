package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.EntityMap;
import dev.crossmc.bridge.Protocol;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host entity &lt;-&gt; Minecraft proxy entity binding (logical server side).
 *
 * <p>Host creatures/bosses/items are represented by hidden, gravity-less armor-stand proxies
 * spawned on the integrated server, so Minecraft's <b>native</b> damage/explosion logic can act on
 * them (see {@link DamageBridge}). Each proxy carries {@code crossmc_proxy} and
 * {@code crossmc_cid_<crossEntityId>} tags.
 *
 * <p><b>Keying:</b> proxies are keyed by the stable CrossMC {@code crossEntityId} (the single
 * cross-game key), never by the Minecraft entity id or the host-native id. Lifecycle is derived
 * from the whole-table snapshot: a key present = spawn/update, absent (or dead) = destroy, so
 * entities removed on the host side, room/scene changes and restarts never leave stale proxies.
 *
 * <p>A proxy is only spawned on the logical server (singleplayer/LAN integrated server); on a
 * remote dedicated server the client cannot create server entities, a documented limitation.
 */
public final class HostEntityManager {
	private static final Map<Integer, ArmorStandEntity> PROXIES = new HashMap<>();
	private static final Set<Integer> SEEN = new HashSet<>();

	private HostEntityManager() {
	}

	/** Stable key: CrossEntityId, falling back to the host-native id if the host did not set one. */
	private static int keyOf(EntityMap row) {
		return row.crossEntityId != 0 ? row.crossEntityId : row.hostEntityId;
	}

	public static void tick(MinecraftServer server) {
		BridgeMemory memory = CrossMcMinecraftClient.memory();

		if (memory == null) {
			return;
		}

		if (!memory.hostAlive(System.currentTimeMillis())) {
			clearAll("host disconnected");
			return;
		}

		EntityMap[] rows;

		try {
			rows = memory.readEntityTable();
		} catch (RuntimeException e) {
			return;
		}

		ServerWorld world = activeWorld(server);

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

			int key = keyOf(row);
			SEEN.add(key);
			ArmorStandEntity stand = PROXIES.get(key);

			if (stand == null || stand.isRemoved()) {
				PROXIES.put(key, spawn(world, row, key));
			} else {
				stand.refreshPositionAndAngles(row.x, row.y, row.z, row.yaw, row.pitch);
			}
		}

		Iterator<Map.Entry<Integer, ArmorStandEntity>> it = PROXIES.entrySet().iterator();
		int removed = 0;

		while (it.hasNext()) {
			Map.Entry<Integer, ArmorStandEntity> entry = it.next();

			if (!SEEN.contains(entry.getKey())) {
				entry.getValue().discard();
				it.remove();
				removed++;
			}
		}

		if (removed > 0) {
			CrossMcMinecraft.LOGGER.info("CrossMC: removed {} stale proxy entities ({} active)", removed, PROXIES.size());
		}
	}

	private static void clearAll(String reason) {
		if (PROXIES.isEmpty()) {
			return;
		}

		for (ArmorStandEntity stand : PROXIES.values()) {
			stand.discard();
		}

		PROXIES.clear();
		CrossMcMinecraft.LOGGER.info("CrossMC: proxy entities removed ({})", reason);
	}

	/** The world the local player is in (proxies must live in the player's dimension). */
	private static ServerWorld activeWorld(MinecraftServer server) {
		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();

		if (!players.isEmpty()) {
			ServerWorld world = players.get(0).getServerWorld();

			if (world != null) {
				return world;
			}
		}

		return server.getOverworld();
	}

	private static ArmorStandEntity spawn(ServerWorld world, EntityMap row, int key) {
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
		CrossMcMinecraft.LOGGER.info("CrossMC: spawned proxy for CrossEntityId {} (host {}) kind={} at ({}, {}, {})",
				key, row.hostEntityId, row.kind, row.x, row.y, row.z);
		return stand;
	}

	public static int proxyCount() {
		return PROXIES.size();
	}
}
