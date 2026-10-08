package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.Protocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * CrossMC Minecraft client entry point.
 *
 * <p>Opens the CrossMC shared memory, publishes the frame (until the host consumes it), the
 * player state and the host-collision proxies. The logical-server half (entity proxies + damage
 * capture) is in {@link CrossMcMinecraft}.
 */
public class CrossMcMinecraftClient implements ClientModInitializer {
	public static final String MOD_ID = "crossmc";

	private static BridgeMemory memory;
	private static int tickCounter;

	public static BridgeMemory memory() {
		return memory;
	}

	@Override
	public void onInitializeClient() {
		try {
			memory = BridgeMemory.open();
			int mcPid = (int) ProcessHandle.current().pid();

			if (!memory.hasValidHeader()) {
				memory.initHeader(0, mcPid);
				memory.initTripleBuffer();
				memory.initTables();
				CrossMcMinecraft.LOGGER.info("created shared memory header (mcPid={})", mcPid);
			} else {
				memory.writeMcPid(mcPid);
				CrossMcMinecraft.LOGGER.info("joined existing shared memory (mcPid={})", mcPid);
			}

			memory.writeMcHeartbeat(System.currentTimeMillis());
			memory.writeMcCapabilities(Protocol.CAP_FRAME | Protocol.CAP_STATE | Protocol.CAP_ENTITY
					| Protocol.CAP_COLLISION | Protocol.CAP_DAMAGE | Protocol.CAP_INPUT);
			FrameExporter.register();

			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				McStatePublisher.tick(client);

				// Colliders change slowly; a couple of times per second is plenty.
				if ((tickCounter++ & 1) == 0) {
					HostCollisionManager.get().refresh();
				}
			});

			CrossMcMinecraft.LOGGER.info("CrossMC bridge ready: {}", Protocol.mappingPath());
		} catch (Exception e) {
			CrossMcMinecraft.LOGGER.error("CrossMC failed to open shared memory at {}", Protocol.mappingPath(), e);
		}
	}
}
