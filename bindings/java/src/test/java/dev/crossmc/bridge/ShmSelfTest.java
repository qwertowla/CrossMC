package dev.crossmc.bridge;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Standalone verification of the Java binding, without Minecraft or the host.
 *
 * <p>It exercises the real file-backed mapping: header init, triple-buffer publish/acquire, slot
 * headers and pixel copy, plus HostState/McState seqlock round-trips. Run:
 * {@code java dev.crossmc.bridge.ShmSelfTest}.
 */
public final class ShmSelfTest {
	public static void main(String[] args) throws Exception {
		System.out.println("config:  " + Protocol.configSource());
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
				check(reader.slotBufferIndex(slotA) == slotA, "buffer index A matches the slot");
				check(reader.slotSequence(slotA) > 0, "slot sequence A stamped");
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

				// ---- seqlock state round-trips (host -> MC, MC -> host) ----
				HostState host = new HostState();
				host.flags = HostState.IN_GAME;
				host.worldId = 7;
				host.timestampMs = 123456789L;
				host.posX = 1.5;
				host.posY = 64.25;
				host.posZ = -13.75;
				host.yaw = 90f;
				host.pitch = -12.5f;
				host.roll = 3f;
				host.unitsPerBlock = 1.0f;
				host.teleportSeq = 2;
				host.viewportW = 1920;
				host.viewportH = 1080;
				writer.writeHostState(host);

				HostState hostRead = reader.readHostState();
				check(hostRead.flags == host.flags && hostRead.worldId == host.worldId, "HostState flags/world");
				check(Double.compare(hostRead.posX, host.posX) == 0
						&& Double.compare(hostRead.posY, host.posY) == 0
						&& Double.compare(hostRead.posZ, host.posZ) == 0, "HostState position");
				check(hostRead.yaw == host.yaw && hostRead.pitch == host.pitch && hostRead.roll == host.roll,
						"HostState rotation (incl. roll)");
				check(hostRead.timestampMs == host.timestampMs, "HostState timestamp");

				McState mc = new McState();
				mc.flags = McState.IN_WORLD | McState.ON_GROUND;
				mc.timestampMs = 987654321L;
				mc.x = -0.5;
				mc.y = 72.0;
				mc.z = 1024.125;
				mc.prevY = 71.9;
				mc.curY = 72.0;
				mc.yaw = 180f;
				mc.pitch = 45f;
				mc.eyeHeight = 1.62f;
				mc.fovDeg = 70f;
				mc.tickMs = 50f;
				mc.frameCounter = 42L;
				writer.writeMcState(mc);

				McState mcRead = reader.readMcState();
				check(mcRead.flags == mc.flags, "McState flags");
				check(Double.compare(mcRead.x, mc.x) == 0
						&& Double.compare(mcRead.z, mc.z) == 0, "McState position");
				check(mcRead.prevY == mc.prevY && Double.compare(mcRead.curY, mc.curY) == 0, "McState tick echo");
				check(mcRead.yaw == mc.yaw && mcRead.pitch == mc.pitch, "McState rotation");
				check(mcRead.frameCounter == mc.frameCounter, "McState frame counter");
				check(mcRead.timestampMs == mc.timestampMs, "McState timestamp");

				// ---- collider table (host -> MC) ----
				writer.writeColliderTable(new Collider[] {
						new Collider(7, Protocol.COLLIDER_BOX, 1f, 2f, 3f, 0.5f, 1f, 0.5f)
				});
				Collider[] colliders = reader.readColliderTable();
				check(colliders.length == 1 && colliders[0].id == 7
						&& colliders[0].halfX == 0.5f && colliders[0].halfY == 1f, "collider table round-trip");

				// ---- entity table (host -> MC) ----
				EntityMap entity = new EntityMap();
				entity.hostEntityId = 42;
				entity.kind = Protocol.ENTITY_CREATURE;
				entity.x = 1f;
				entity.y = 2f;
				entity.z = 3f;
				entity.health = 10f;
				entity.maxHealth = 20f;
				writer.writeEntityTable(new EntityMap[] {entity});
				EntityMap[] entities = reader.readEntityTable();
				check(entities.length == 1 && entities[0].hostEntityId == 42
						&& entities[0].kind == Protocol.ENTITY_CREATURE
						&& entities[0].health == 10f, "entity table round-trip");

				// ---- damage ring (MC -> host) ----
				DamageEvent damage = new DamageEvent();
				damage.hostEntityId = 42;
				damage.mcEntityId = 1234;
				damage.sourceType = Protocol.DMG_EXPLOSION;
				damage.amount = 20f;
				writer.pushDamage(damage);
				DamageEvent damageRead = reader.pollDamage();
				check(damageRead != null && damageRead.hostEntityId == 42
						&& damageRead.sourceType == Protocol.DMG_EXPLOSION
						&& damageRead.amount == 20f, "damage ring round-trip");
				check(reader.pollDamage() == null, "damage ring empty after consuming");
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
