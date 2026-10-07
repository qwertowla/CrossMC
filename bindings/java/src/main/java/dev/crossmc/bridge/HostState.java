package dev.crossmc.bridge;

/**
 * Java mirror of {@code crossmc_host_state} in {@code protocol/bridge_protocol.h}.
 *
 * <p>Written by the host (How to Fish) and read by Minecraft. Small latest-value struct, guarded
 * by a seqlock (see {@link BridgeMemory#writeHostState}/{@link BridgeMemory#readHostState}).
 */
public final class HostState {
	public int flags;
	public int worldId;
	public int collisionEpoch;
	public long timestampMs;      // epoch ms
	public double posX, posY, posZ;
	public float yaw, pitch, roll; // MC degrees
	public float eyeHeight;
	public float unitsPerBlock;
	public int teleportSeq;
	public int cameraMode;
	public int viewportW, viewportH;

	// ---- flag bits (CROSSMC_HOST_*) ----
	public static final int IN_GAME = 1 << 0;
	public static final int MENU_OPEN = 1 << 1;
	public static final int LOADING = 1 << 2;
}
