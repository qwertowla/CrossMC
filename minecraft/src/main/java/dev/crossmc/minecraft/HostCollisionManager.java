package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.Collider;
import dev.crossmc.bridge.Protocol;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;

/**
 * Client-side host-collider proxy.
 *
 * <p>Reads the host collider table from shared memory and voxelises each collider into a set of
 * occupied block cells. {@code WorldBlockStateMixin} then makes
 * {@code ClientWorld.getBlockState(cell)} return a solid, invisible {@code barrier} block for
 * those cells, so Minecraft's <b>native</b> collision, raycast and placement see host space
 * without re-implementing any Minecraft rule.
 *
 * <p>Colliders are quantised to blocks (explicitly allowed by the spec); rotation is currently
 * ignored (the AABB is inflated to be conservative). All game access happens through
 * {@code World.getBlockState} on the client thread.
 */
public final class HostCollisionManager {
	private static final HostCollisionManager INSTANCE = new HostCollisionManager();

	/** Hard caps so a bad host collider cannot freeze the client. */
	private static final int MAX_CELLS = 262144;
	private static final int MAX_AXIS_SPAN = 128;

	private final LongOpenHashSet cells = new LongOpenHashSet();
	private volatile boolean enabled;
	private volatile int colliderCount;
	private volatile int cellCount;

	private HostCollisionManager() {
	}

	public static HostCollisionManager get() {
		return INSTANCE;
	}

	public static boolean enabled() {
		return INSTANCE.enabled;
	}

	/** Client thread. True when host space occupies this block cell. */
	public boolean isPhantom(BlockPos pos) {
		return enabled && cells.contains(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()));
	}

	/** Re-reads the collider table and rebuilds the cell set. Client thread only. */
	public void refresh() {
		BridgeMemory memory = CrossMcMinecraftClient.memory();

		if (memory == null) {
			enabled = false;
			cells.clear();
			return;
		}

		if (!memory.hostAlive(System.currentTimeMillis())) {
			if (enabled) {
				enabled = false;
				cells.clear();
				CrossMcMinecraft.LOGGER.info("CrossMC: host disconnected — collision proxies cleared");
			}

			return;
		}

		Collider[] colliders;

		try {
			colliders = memory.readColliderTable();
		} catch (RuntimeException e) {
			return;
		}

		LongOpenHashSet next = new LongOpenHashSet();

		for (Collider c : colliders) {
			if ((c.flags & Protocol.COLLIDER_ENABLED) == 0) {
				continue;
			}

			voxelize(next, c);

			if (next.size() > MAX_CELLS) {
				break;
			}
		}

		cells.clear();
		cells.addAll(next);
		colliderCount = colliders.length;
		cellCount = next.size();
		enabled = !next.isEmpty();
	}

	private static void voxelize(LongOpenHashSet out, Collider c) {
		float hx;
		float hy;
		float hz;

		switch (c.type) {
			case Protocol.COLLIDER_SPHERE -> hx = hy = hz = c.halfX;
			case Protocol.COLLIDER_CAPSULE -> {
				hx = c.halfX;
				hy = c.halfY + c.halfX;
				hz = c.halfX;
			}
			default -> {
				hx = c.halfX;
				hy = c.halfY;
				hz = c.halfZ;
			}
		}

		int minX = (int) Math.floor(c.centerX - hx);
		int maxX = (int) Math.floor(c.centerX + hx);
		int minY = (int) Math.floor(c.centerY - hy);
		int maxY = (int) Math.floor(c.centerY + hy);
		int minZ = (int) Math.floor(c.centerZ - hz);
		int maxZ = (int) Math.floor(c.centerZ + hz);

		if (maxX - minX > MAX_AXIS_SPAN || maxY - minY > MAX_AXIS_SPAN || maxZ - minZ > MAX_AXIS_SPAN) {
			return;
		}

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					out.add(BlockPos.asLong(x, y, z));
				}
			}
		}
	}

	public int colliderCount() {
		return colliderCount;
	}

	public int cellCount() {
		return cellCount;
	}
}
