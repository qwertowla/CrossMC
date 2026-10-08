package dev.crossmc.bridge;

/**
 * Java mirror of {@code crossmc_collider} in {@code protocol/bridge_protocol.h}.
 *
 * <p>A host collider published by the host adapter (in Minecraft space). Minecraft voxelises
 * these into client-side collision proxies. {@link #id} is stable for the collider's lifetime.
 */
public final class Collider {
	public int id;
	public int type;                 // Protocol.COLLIDER_*
	public int flags;                // Protocol.COLLIDER_*
	public float centerX, centerY, centerZ;
	public float halfX, halfY, halfZ; // box half extents; sphere r=halfX; capsule r=halfX, halfH=halfY
	public float rotYaw;             // degrees about +Y
	public long updatedMs;

	public Collider() {
	}

	public Collider(int id, int type, float cx, float cy, float cz, float hx, float hy, float hz) {
		this.id = id;
		this.type = type;
		this.flags = Protocol.COLLIDER_ENABLED;
		this.centerX = cx;
		this.centerY = cy;
		this.centerZ = cz;
		this.halfX = hx;
		this.halfY = hy;
		this.halfZ = hz;
	}
}
