package dev.crossmc.minecraft;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.Protocol;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

/**
 * CrossMC Minecraft frame producer (Phase 1).
 *
 * <p>After the world has been rendered into the client's main framebuffer, this reads the colour
 * attachment back on the CPU ({@code glReadPixels}) and publishes it into a triple-buffer slot in
 * the shared memory as packed <b>BGRA8</b>. The host adapter consumes it.
 *
 * <p>Threading: {@link WorldRenderEvents#END} runs on Minecraft's render thread, which is the only
 * safe place to touch GL. The protocol write itself is plain memory. Running on the same render
 * thread means the readback may stall the pipeline; {@link #MAX_EXPORT_FPS} keeps that bounded and
 * the game playable while the slice is validated.
 *
 * <p>Scope: the world image only. The hook fires before the hand and the GUI/HUD are drawn, so this
 * rectangle does not include them. Including the HUD would mean moving the hook after GUI rendering.
 * Rows are bottom-up (OpenGL origin), flagged via {@link Protocol#OVERLAY_BOTTOM_UP}.
 */
public final class FrameExporter {
	/** {@code GL_READ_FRAMEBUFFER}; only the read target is touched, so drawing is unaffected. */
	private static final int GL_READ_FRAMEBUFFER = 0x8CA8;

	/**
	 * Cap on exported frames per second. 0 exports every frame (correct but heavy: a full-resolution
	 * GPU->CPU readback each frame). Override with {@code -Dcrossmc.exportFps=N}.
	 */
	private static final int MAX_EXPORT_FPS = Integer.getInteger("crossmc.exportFps", 15);

	/** Reused direct readback buffer, reallocated only when the framebuffer resizes. */
	private static ByteBuffer readback;
	private static int readbackW;
	private static int readbackH;
	private static long lastPublishNs;

	private FrameExporter() {
	}

	/** Registers the frame export hook. Call once, from client initialisation. */
	public static void register() {
		WorldRenderEvents.END.register(context -> {
			try {
				capture();
			} catch (Throwable t) {
				CrossMcMinecraft.LOGGER.error("CrossMC frame export failed", t);
			}
		});
	}

	private static void capture() {
		BridgeMemory memory = CrossMcMinecraftClient.memory();

		if (memory == null) {
			return;
		}

		Framebuffer framebuffer = MinecraftClient.getInstance().getFramebuffer();

		if (framebuffer == null) {
			return;
		}

		int width = framebuffer.textureWidth;
		int height = framebuffer.textureHeight;

		if (width <= 0 || height <= 0 || width > Protocol.MAX_FRAME_W || height > Protocol.MAX_FRAME_H) {
			return;
		}

		long now = System.nanoTime();

		if (MAX_EXPORT_FPS > 0 && now - lastPublishNs < 1_000_000_000L / MAX_EXPORT_FPS) {
			return;
		}

		int bytes = width * height * Protocol.BYTES_PER_PIXEL;

		if (readback == null || readbackW != width || readbackH != height) {
			readback = ByteBuffer.allocateDirect(bytes);
			readbackW = width;
			readbackH = height;
		}

		// Bind only the READ target for the colour attachment, pack tightly, read BGRA8.
		GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer.fbo);
		GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
		readback.clear();
		GlStateManager._readPixels(0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, readback);
		GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
		GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);

		memory.publishFrame(readback, width, height, Protocol.OVERLAY_BOTTOM_UP);
		memory.writeMcHeartbeat(System.currentTimeMillis());
		lastPublishNs = now;
	}
}
