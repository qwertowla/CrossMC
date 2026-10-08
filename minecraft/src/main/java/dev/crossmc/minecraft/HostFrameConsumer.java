package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.Protocol;
import dev.crossmc.minecraft.mixin.NativeImageAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Consumes the CrossMC <b>host frame</b> channel (host -&gt; Minecraft) into a Minecraft dynamic
 * texture ({@link NativeImageBackedTexture}), created once and updated in place.
 *
 * <p>Runs on Minecraft's <b>render thread</b> (via {@link WorldRenderEvents#END}) because uploading a
 * texture touches GL. The host is the lock-free triple-buffer writer, so a missed frame just means we
 * skip it; nothing here can block the player/state/entity/collider/damage channels. On failure the
 * frame is dropped, never retried synchronously.
 */
public final class HostFrameConsumer {
	private static final Identifier TEXTURE_ID = Identifier.of("crossmc", "host_frame");

	private static NativeImageBackedTexture texture;
	private static int texW;
	private static int texH;
	private static byte[] pixelBuffer;
	private static boolean hasFrame;
	private static long lastSeq = -1;
	private static long consumed;
	private static long dropped;
	private static long lastLogMs;

	private HostFrameConsumer() {
	}

	/** The dynamic texture holding the newest host frame, or {@code null} before the first frame. */
	public static Identifier textureId() {
		return texture != null ? TEXTURE_ID : null;
	}

	public static boolean hasFrame() {
		return hasFrame;
	}

	public static void register() {
		WorldRenderEvents.END.register(context -> tick());
	}

	private static void tick() {
		BridgeMemory memory = CrossMcMinecraftClient.memory();

		if (memory == null) {
			return;
		}

		int slot;

		try {
			slot = memory.hostFrameAcquire();
		} catch (RuntimeException e) {
			return;
		}

		if (slot < 0) {
			return;
		}

		long seq = memory.hostFrameSlotSequence(slot);

		if (seq == lastSeq) {
			return;
		}

		if (lastSeq >= 0 && seq > lastSeq + 1) {
			dropped += seq - lastSeq - 1;
		}

		lastSeq = seq;

		int w = memory.hostFrameSlotWidth(slot);
		int h = memory.hostFrameSlotHeight(slot);
		int stride = memory.hostFrameSlotStride(slot);
		int format = memory.hostFrameSlotFormat(slot);
		int flags = memory.hostFrameSlotFlags(slot);

		if (w <= 0 || h <= 0 || w > Protocol.MAX_HOSTFRAME_W || h > Protocol.MAX_HOSTFRAME_H) {
			return;
		}

		int need = stride * h;
		int cap = Protocol.MAX_HOSTFRAME_W * Protocol.MAX_HOSTFRAME_H * Protocol.BYTES_PER_PIXEL;

		if (need <= 0 || need > cap) {
			return;
		}

		if (pixelBuffer == null || pixelBuffer.length != need) {
			pixelBuffer = new byte[need];
		}

		try {
			memory.hostFrameReadPixels(slot, pixelBuffer);

			if ((flags & Protocol.OVERLAY_BOTTOM_UP) != 0) {
				flipVertically(pixelBuffer, w, h, stride);
			}

			if (format == Protocol.FORMAT_BGRA8) {
				swapRedBlue(pixelBuffer, need);
			}

			upload(w, h);
		} catch (RuntimeException e) {
			// Drop the frame; never stall the video path or any other channel.
			dropped++;
			return;
		}

		consumed++;
		hasFrame = true;

		long now = System.currentTimeMillis();

		if (now - lastLogMs >= 2000) {
			lastLogMs = now;
			CrossMcMinecraft.LOGGER.info("CrossMC HostFrame: seq={} size={}x{} age={}ms consumed={} dropped={}",
					seq, w, h, now - memory.hostFrameSlotTimestampMs(slot), consumed, dropped);
		}
	}

	private static void upload(int w, int h) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (texture == null || texW != w || texH != h) {
			if (texture != null) {
				texture.close();
			}

			NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false);
			texture = new NativeImageBackedTexture(image);
			client.getTextureManager().registerTexture(TEXTURE_ID, texture);
			texW = w;
			texH = h;
		}

		NativeImage image = texture.getImage();
		NativeImageAccessor accessor = (NativeImageAccessor) (Object) image;
		long pointer = accessor.crossmc$getPointer();
		long size = accessor.crossmc$getSizeBytes();
		ByteBuffer buffer = MemoryUtil.memByteBuffer(pointer, (int) size);
		buffer.clear();
		buffer.put(pixelBuffer, 0, (int) Math.min(size, pixelBuffer.length));
		texture.upload();
	}

	private static void swapRedBlue(byte[] bytes, int count) {
		for (int i = 0; i + 3 < count; i += 4) {
			byte t = bytes[i];
			bytes[i] = bytes[i + 2];
			bytes[i + 2] = t;
		}
	}

	private static void flipVertically(byte[] bytes, int width, int height, int stride) {
		byte[] row = new byte[stride];

		for (int y = 0; y < height / 2; y++) {
			int top = y * stride;
			int bottom = (height - 1 - y) * stride;
			System.arraycopy(bytes, top, row, 0, stride);
			System.arraycopy(bytes, bottom, bytes, top, stride);
			System.arraycopy(row, 0, bytes, bottom, stride);
		}
	}
}
