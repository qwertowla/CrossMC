package dev.crossmc.bridge;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Standalone verification of the Java binding, without Minecraft or the host.
 *
 * <p>It exercises the real file-backed mapping: header init, triple-buffer publish/acquire,
 * slot headers and pixel copy. Run: {@code java dev.crossmc.bridge.ShmSelfTest}.
 */
public final class ShmSelfTest {
	public static void main(String[] args) throws Exception {
		System.out.println("config:  " + Protocol.configFile());
		System.out.println("mapping: " + Protocol.mappingPath());

		Path path = Protocol.mappingPath();
		Files.deleteIfExists(path);

		try (BridgeMemory writer = BridgeMemory.open()) {
			if (!writer.hasValidHeader()) {
				writer.initHeader(1111, 2222);
				writer.initTripleBuffer();
				System.out.println("creator: header + triple buffer initialised");
			}

			// A second, independent mapping acts as the other process.
			try (BridgeMemory reader = BridgeMemory.open()) {
				check(reader.hasValidHeader(), "reader sees a valid header");

				check(reader.acquire() == -1, "no frame before first publish");

				byte[] a = pattern(4, 2, 0x10);
				writer.publishFrame(a, 4, 2, Protocol.OVERLAY_BOTTOM_UP);

				int slotA = reader.acquire();
				check(slotA >= 0, "first frame acquired");
				check(reader.slotWidth(slotA) == 4 && reader.slotHeight(slotA) == 2, "frame size A");
				check(reader.slotFormat(slotA) == Protocol.FORMAT_BGRA8, "format A");
				byte[] gotA = new byte[4 * 2 * 4];
				reader.readPixels(slotA, gotA);
				check(Arrays.equals(a, gotA), "pixels A round-trip");

				byte[] b = pattern(4, 2, 0xB0);
				writer.publishFrame(b, 4, 2, 0);
				int slotB = reader.acquire();
				check(slotB >= 0 && slotB != slotA, "second frame acquired and uses another slot");
				byte[] gotB = new byte[4 * 2 * 4];
				reader.readPixels(slotB, gotB);
				check(Arrays.equals(b, gotB), "pixels B round-trip");

				// The exporter's path: publish straight from a direct buffer, like glReadPixels.
				byte[] c = pattern(4, 2, 0x40);
				ByteBuffer direct = ByteBuffer.allocateDirect(c.length);
				direct.put(c).flip();
				writer.publishFrame(direct, 4, 2, Protocol.OVERLAY_BOTTOM_UP);

				int slotC = reader.acquire();
				check(slotC >= 0, "direct-buffer frame acquired");
				byte[] gotC = new byte[c.length];
				reader.readPixels(slotC, gotC);
				check(Arrays.equals(c, gotC), "direct-buffer pixels round-trip");

				check(reader.slotFlags(slotC) == Protocol.OVERLAY_BOTTOM_UP, "direct frame bottom-up flag");

				check(reader.acquire() == -1, "no fresh frame after consuming");
			}
		}

		System.out.println("ShmSelfTest: PASS");
	}

	private static byte[] pattern(int w, int h, int seed) {
		byte[] px = new byte[w * h * 4];
		for (int i = 0; i < px.length; i++) {
			px[i] = (byte) (seed + i);
		}
		return px;
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new IllegalStateException("FAIL: " + what);
		}
		System.out.println("ok: " + what);
	}
}
