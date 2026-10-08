package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.HostState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * One-time player bootstrap (client).
 *
 * <p>When a new Minecraft world/session loads, the Minecraft player would otherwise appear at the
 * save position and the host would be pulled there. Instead, for a short initialisation phase the
 * host publishes a {@link HostState#BOOTSTRAP} request with the host avatar's mapped MC position
 * ({@code HostState.pos}) and a generation ({@code teleportSeq}); here we move the Minecraft player
 * there once, then report {@code McState.BOOTSTRAP_DONE} and hand authority back to Minecraft
 * (normal MC -> host following).
 *
 * <p>The player is teleported through the <b>integrated server</b> ({@code ServerPlayerEntity.teleport})
 * so the client and server agree. CoordinateMapper is not re-anchored here.
 */
public final class PlayerBootstrapManager {
	private static final double CONFIRM_DISTANCE = 2.5;

	private static ClientWorld lastWorld;
	private static boolean pending;
	private static boolean done = true;
	private static int handledSeq;
	private static double targetX;
	private static double targetY;
	private static double targetZ;
	private static long lastLogMs;

	private PlayerBootstrapManager() {
	}

	/** True when Minecraft is not mid-bootstrap, so the host may follow normally. */
	public static boolean isBootstrapped() {
		return !pending && done;
	}

	public static boolean isPending() {
		return pending;
	}

	public static void tick(MinecraftClient client, BridgeMemory memory) {
		if (memory == null) {
			return;
		}

		ClientWorld world = client.world;

		if (world != lastWorld) {
			// New world/session: arm a fresh bootstrap.
			lastWorld = world;
			pending = false;
			handledSeq = 0;
			done = world == null;
			log("MC world session changed (world=" + (world == null ? "null" : "loaded")
					+ ") — bootstrap re-armed, handledSeq=" + handledSeq);
		}

		if (world == null || client.player == null) {
			return;
		}

		HostState host;

		try {
			host = memory.readHostState();
		} catch (RuntimeException e) {
			return;
		}

		boolean requested = (host.flags & HostState.BOOTSTRAP) != 0;

		if (!requested) {
			pending = false;
			done = true;
			return;
		}

		if (host.teleportSeq != handledSeq) {
			handledSeq = host.teleportSeq;
			targetX = host.posX;
			targetY = host.posY;
			targetZ = host.posZ;
			pending = true;
			done = false;
			double fromX = client.player.getX();
			double fromY = client.player.getY();
			double fromZ = client.player.getZ();
			teleport(client, targetX, targetY, targetZ);
			log("TELEPORT from (" + fmt(fromX) + ", " + fmt(fromY) + ", " + fmt(fromZ)
					+ ") to (" + fmt(targetX) + ", " + fmt(targetY) + ", " + fmt(targetZ)
					+ ") seq=" + host.teleportSeq);
		}

		if (pending) {
			freezeInput(client.player);
			double dx = client.player.getX() - targetX;
			double dy = client.player.getY() - targetY;
			double dz = client.player.getZ() - targetZ;
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

			if (distance < CONFIRM_DISTANCE) {
				pending = false;
				done = true;
				log("completed (distance=" + fmt(distance) + ") — player authority is Minecraft");
			} else {
				logThrottled("waiting confirmation distance=" + fmt(distance));
			}
		}
	}

	private static void teleport(MinecraftClient client, double x, double y, double z) {
		ClientPlayerEntity player = client.player;
		player.setVelocity(0.0, 0.0, 0.0);
		player.setPosition(x, y, z);

		IntegratedServer server = client.getServer();

		if (server != null) {
			ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(player.getUuid());

			if (serverPlayer != null) {
				serverPlayer.teleport(serverPlayer.getServerWorld(), x, y, z,
						serverPlayer.getYaw(), serverPlayer.getPitch());
			}
		}
	}

	private static void freezeInput(ClientPlayerEntity player) {
		if (player.input != null) {
			player.input.movementForward = 0f;
			player.input.movementSideways = 0f;
			player.input.jumping = false;
			player.input.sneaking = false;
		}

		player.setVelocity(0.0, 0.0, 0.0);
	}

	private static String fmt(double v) {
		return String.format("%.2f", v);
	}

	private static void log(String message) {
		lastLogMs = System.currentTimeMillis();
		CrossMcMinecraft.LOGGER.info("CrossMC bootstrap: {}", message);
	}

	private static void logThrottled(String message) {
		long now = System.currentTimeMillis();

		if (now - lastLogMs < 1000) {
			return;
		}

		lastLogMs = now;
		CrossMcMinecraft.LOGGER.info("CrossMC bootstrap: {}", message);
	}
}
