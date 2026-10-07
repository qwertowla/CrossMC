package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.Protocol;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CrossMC Minecraft client entry point.
 *
 * <p>Phase 1 scaffold: opens the CrossMC shared memory and publishes a heartbeat. The frame
 * exporter (render target -> CPU readback -> {@link BridgeMemory#publishFrame}) lands next.
 */
public class CrossMcMinecraftClient implements ClientModInitializer {
	public static final String MOD_ID = "crossmc";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static BridgeMemory memory;

	public static BridgeMemory memory() {
		return memory;
	}

	@Override
	public void onInitializeClient() {
		try {
			memory = BridgeMemory.open();
			int mcPid = (int) ProcessHandle.current().pid();

			if (!memory.hasValidHeader()) {
				// We got here first: create the header and seed the triple buffer.
				memory.initHeader(0, mcPid);
				memory.initTripleBuffer();
				LOGGER.info("created shared memory header (mcPid={})", mcPid);
			} else {
				// The host (or a previous session) owns the header; just register this session.
				memory.writeMcPid(mcPid);
				LOGGER.info("joined existing shared memory (mcPid={})", mcPid);
			}

			memory.writeMcHeartbeat(System.currentTimeMillis());
			FrameExporter.register();
			LOGGER.info("CrossMC bridge ready: {}", Protocol.mappingPath());
		} catch (Exception e) {
			LOGGER.error("CrossMC failed to open shared memory at {}", Protocol.mappingPath(), e);
		}
	}
}
