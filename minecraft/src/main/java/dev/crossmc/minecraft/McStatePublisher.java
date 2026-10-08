package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.McState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Publishes {@link McState} (Minecraft -> host) on the client tick, guarded by a seqlock.
 *
 * <p>Carries the interpolated player transform, rotation, eye height, camera mode and a tick echo
 * so the host can drive its own representation of the (logically primary) Minecraft player.
 */
public final class McStatePublisher {
	private static final McState STATE = new McState();
	private static long frames;
	private static int nullTicks;

	private McStatePublisher() {
	}

	public static void tick(MinecraftClient client) {
		BridgeMemory memory = CrossMcMinecraftClient.memory();

		if (memory == null) {
			return;
		}

		ClientPlayerEntity player = client.player;

		if (player == null) {
			// Not in a world (title screen / loading): Minecraft is NOT publishing state.
			if (++nullTicks % 100 == 1) {
				CrossMcMinecraft.LOGGER.info("CrossMC: not publishing state — no client player (title screen / loading)");
			}

			return;
		}

		Vec3d pos = player.getPos();
		int flags = McState.IN_WORLD;

		if (player.isOnGround()) {
			flags |= McState.ON_GROUND;
		}

		if (player.isSneaking()) {
			flags |= McState.SNEAKING;
		}

		if (player.isSprinting()) {
			flags |= McState.SPRINTING;
		}

		if (player.isSwimming()) {
			flags |= McState.SWIMMING;
		}

		if (player.isDead()) {
			flags |= McState.DEAD;
		}

		if (player.getAbilities().flying) {
			flags |= McState.FLYING;
		}

		STATE.flags = flags;
		STATE.timestampMs = System.currentTimeMillis();
		STATE.x = pos.x;
		STATE.y = pos.y;
		STATE.z = pos.z;
		STATE.prevX = player.prevX;
		STATE.prevY = player.prevY;
		STATE.prevZ = player.prevZ;
		STATE.curX = pos.x;
		STATE.curY = pos.y;
		STATE.curZ = pos.z;
		STATE.yaw = player.getYaw();
		STATE.pitch = player.getPitch();
		STATE.eyeHeight = player.getStandingEyeHeight();
		STATE.fovDeg = 70f;
		STATE.tickMs = 50f;
		STATE.cameraMode = client.options.getPerspective().isFirstPerson() ? 0 : 1;
		STATE.health = Math.round(player.getHealth());
		STATE.hunger = player.getHungerManager().getFoodLevel();
		STATE.frameCounter = ++frames;
		memory.writeMcState(STATE);
		memory.writeMcHeartbeat(System.currentTimeMillis());

		if (frames % 100 == 0) {
			CrossMcMinecraft.LOGGER.info("CrossMC: publishing McState — player at ({}, {}, {}), flags={}, health={}, hunger={}",
					String.format("%.1f", pos.x), String.format("%.1f", pos.y), String.format("%.1f", pos.z),
					flags, STATE.health, STATE.hunger);
		}
	}
}
