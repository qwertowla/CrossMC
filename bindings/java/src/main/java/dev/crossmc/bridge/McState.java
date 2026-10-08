package dev.crossmc.bridge;

/**
 * Java mirror of {@code crossmc_mc_state} in {@code protocol/bridge_protocol.h}.
 *
 * <p>Written by Minecraft and read by the host. Small latest-value struct, guarded by a seqlock
 * (see {@link BridgeMemory#writeMcState}/{@link BridgeMemory#readMcState}).
 *
 * <p><b>Authority:</b> Minecraft is the main game and the Minecraft player is the ONE authoritative
 * player; this is its state and the host mirrors it (player + camera). The host must not override
 * the Minecraft player.
 */
public final class McState {
	public int flags;
	public long timestampMs;      // epoch ms
	public double x, y, z;        // interpolated feet position (MC space)
	public double prevX, prevY, prevZ; // previous tick
	public double curX, curY, curZ;    // current tick
	public float yaw, pitch;      // MC degrees
	public float eyeHeight;
	public float fovDeg;
	public float tickMs;
	public int cameraMode;
	public float cameraDistance;
	public long frameCounter;
	public long tickQpc;
	public int health;            // Minecraft health (0..20) -> host player health
	public int hunger;            // Minecraft food level (0..20) -> host player hunger

	// ---- flag bits (CROSSMC_MC_*) ----
	public static final int IN_WORLD = 1 << 0;
	public static final int SCREEN_OPEN = 1 << 1;
	public static final int ON_GROUND = 1 << 2;
	public static final int SNEAKING = 1 << 3;
	public static final int SPRINTING = 1 << 4;
	public static final int DEAD = 1 << 5;
	public static final int SWIMMING = 1 << 6;
	public static final int FLYING = 1 << 7;
	public static final int BOOTSTRAP_DONE = 1 << 8;
}
