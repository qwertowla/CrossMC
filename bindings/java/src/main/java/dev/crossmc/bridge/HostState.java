package dev.crossmc.bridge;

/**
 * Java mirror of {@code crossmc_host_state} in {@code protocol/bridge_protocol.h}.
 *
 * <p>Written by the host game and read by Minecraft. Small latest-value struct, guarded
 * by a seqlock (see {@link BridgeMemory#writeHostState}/{@link BridgeMemory#readHostState}).
 *
 * <p><b>Authority:</b> the host's own avatar/environment, <b>not</b> the Minecraft player. The
 * position/rotation fields below are informational and must never drive the Minecraft player;
 * host input travels in {@link InputEvent}, and the authoritative player state is in {@link McState}.
 */
public final class HostState {
	public int flags;
	public int worldId;
	public int collisionEpoch;
	public long timestampMs;      // epoch ms
	public double posX, posY, posZ;        // host avatar (informational; NOT MC authority)
	public float yaw, pitch, roll;         // host camera (informational; NOT MC authority)
	public float eyeHeight;
	public float unitsPerBlock;
	public int teleportSeq;
	public int cameraMode;
	public int viewportW, viewportH;

	// ---- flag bits (CROSSMC_HOST_*) ----
	public static final int IN_GAME = 1 << 0;
	public static final int MENU_OPEN = 1 << 1;
	public static final int LOADING = 1 << 2;
	public static final int BOOTSTRAP = 1 << 3;
}
