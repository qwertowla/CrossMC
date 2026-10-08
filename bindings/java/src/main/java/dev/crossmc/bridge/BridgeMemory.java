package dev.crossmc.bridge;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File-backed shared memory between the Minecraft mod and a host adapter.
 *
 * <p>One mapping carries everything (see {@link Protocol}). Coordination uses plain memory access
 * only: a lock-free <b>triple buffer</b> for frames and <b>seqlocks</b> for the small latest-value
 * state structs ({@link HostState} / {@link McState}). The triple-buffer writer (Minecraft) owns
 * {@code back}; the reader (host) owns {@code front}; they meet only at the atomic {@code state}
 * word.
 *
 * <p>The mapping path comes from {@link Protocol#mappingPath()} (configurable; see
 * {@code config/crossmc.properties}).
 *
 * <p>Not thread-safe by itself beyond the described single-writer / single-reader roles.
 */
public final class BridgeMemory implements Closeable {
	// Atomic views over the mapped bytes. Index is a BYTE offset.
	private static final VarHandle STATE =
			MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
	private static final VarHandle SEQ =
			MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

	private static final int SEQ_RETRIES = 256;

	private final RandomAccessFile raf;
	private final FileChannel channel;
	private final MappedByteBuffer map;

	// private roles
	private int back;    // writer's private write slot
	private int front;   // reader's private read slot
	private long writeFrameId;
	private long framesPublished;
	private int hostFrameFront;  // host-frame reader's private front slot (host -> MC)

	private BridgeMemory(RandomAccessFile raf, FileChannel channel, MappedByteBuffer map) {
		this.raf = raf;
		this.channel = channel;
		this.map = map;
	}

	/** Opens (creating and sizing if necessary) the shared mapping resolved from configuration. */
	public static BridgeMemory open() throws IOException {
		Path path = Protocol.mappingPath();
		Files.createDirectories(path.getParent());

		RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
		FileChannel channel = raf.getChannel();

		if (channel.size() < Protocol.MAPPING_BYTES) {
			raf.setLength(Protocol.MAPPING_BYTES);
		}

		MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_WRITE, 0, Protocol.MAPPING_BYTES);
		// The protocol is LITTLE-ENDIAN. A MappedByteBuffer defaults to BIG_ENDIAN, which made the
		// Java (Minecraft) side write a different byte order than the C# side read/write.
		map.order(ByteOrder.LITTLE_ENDIAN);
		return new BridgeMemory(raf, channel, map);
	}

	// ------------------------------------------------------------------ header

	/** Magic, version and the runtime size check all pass. A stale/incompatible file fails. */
	public boolean hasValidHeader() {
		return map.getInt((int) Protocol.HDR_MAGIC) == Protocol.MAGIC
				&& map.getInt((int) Protocol.HDR_VERSION) == Protocol.VERSION
				&& map.getInt((int) Protocol.HDR_HEADER_SIZE) == Protocol.HEADER_SIZE
				&& map.getInt((int) Protocol.HDR_HOST_STATE_SIZE) == Protocol.HOST_STATE_SIZE
				&& map.getInt((int) Protocol.HDR_MC_STATE_SIZE) == Protocol.MC_STATE_SIZE
				&& map.getInt((int) Protocol.HDR_OVERLAY_SLOT_SIZE) == Protocol.OVERLAY_SLOT_SIZE;
	}

	/** Writes the header. Call once by whichever side creates the mapping. */
	public void initHeader(int hostPid, int mcPid) {
		map.putInt((int) Protocol.HDR_MAGIC, Protocol.MAGIC);
		map.putInt((int) Protocol.HDR_VERSION, Protocol.VERSION);
		map.putInt((int) Protocol.HDR_HEADER_SIZE, Protocol.HEADER_SIZE);
		map.putInt((int) Protocol.HDR_MAPPING_BYTES, (int) Protocol.MAPPING_BYTES);
		map.putInt((int) Protocol.HDR_HOST_CAPS, 0);
		map.putInt((int) Protocol.HDR_MC_CAPS, 0);
		map.putInt((int) Protocol.HDR_HOST_PID, hostPid);
		map.putInt((int) Protocol.HDR_MC_PID, mcPid);
		map.putInt((int) Protocol.HDR_HOST_STATE_SIZE, Protocol.HOST_STATE_SIZE);
		map.putInt((int) Protocol.HDR_MC_STATE_SIZE, Protocol.MC_STATE_SIZE);
		map.putInt((int) Protocol.HDR_OVERLAY_SLOT_SIZE, Protocol.OVERLAY_SLOT_SIZE);
		map.putInt((int) Protocol.HDR_INPUT_RING_SIZE,
				Protocol.INPUT_RING_SIZE + Protocol.INPUT_RING_ENTRIES * Protocol.INPUT_EVENT_SIZE);
		map.putLong((int) Protocol.HDR_HOST_HEARTBEAT, 0L);
		map.putLong((int) Protocol.HDR_MC_HEARTBEAT, 0L);
		map.putLong((int) Protocol.HDR_SEQUENCE, 0L);
		map.putLong((int) Protocol.HDR_TIMESTAMP, 0L);
	}

	/** Stamps this process's pid; call on every open so the peer sees the current session. */
	public void writeMcPid(int pid) {
		map.putInt((int) Protocol.HDR_MC_PID, pid);
	}

	public void writeHostPid(int pid) {
		map.putInt((int) Protocol.HDR_HOST_PID, pid);
	}

	/** Unix-epoch milliseconds; shared clock so either side can judge liveness across processes. */
	public void writeMcHeartbeat(long epochMs) {
		map.putLong((int) Protocol.HDR_MC_HEARTBEAT, epochMs);
	}

	public void writeHostHeartbeat(long epochMs) {
		map.putLong((int) Protocol.HDR_HOST_HEARTBEAT, epochMs);
	}

	public long readMcPid() {
		return map.getInt((int) Protocol.HDR_MC_PID) & 0xFFFFFFFFL;
	}

	public long readHostPid() {
		return map.getInt((int) Protocol.HDR_HOST_PID) & 0xFFFFFFFFL;
	}

	public long readMcHeartbeat() {
		return map.getLong((int) Protocol.HDR_MC_HEARTBEAT);
	}

	public long readHostHeartbeat() {
		return map.getLong((int) Protocol.HDR_HOST_HEARTBEAT);
	}

	/** Feature bitmask this process provides (Protocol.CAP_*). */
	public void writeHostCapabilities(int caps) {
		map.putInt((int) Protocol.HDR_HOST_CAPS, caps);
	}

	public void writeMcCapabilities(int caps) {
		map.putInt((int) Protocol.HDR_MC_CAPS, caps);
	}

	public int readHostCapabilities() {
		return map.getInt((int) Protocol.HDR_HOST_CAPS);
	}

	public int readMcCapabilities() {
		return map.getInt((int) Protocol.HDR_MC_CAPS);
	}

	/**
	 * True if the host is alive: its heartbeat is newer than the timeout. When false, consumers
	 * must treat host data (state, entities, colliders, frames) as stale and stop using it.
	 */
	public boolean hostAlive(long nowMs) {
		long hb = readHostHeartbeat();
		return hb != 0 && nowMs - hb <= Protocol.HEARTBEAT_TIMEOUT_MS;
	}

	public boolean mcAlive(long nowMs) {
		long hb = readMcHeartbeat();
		return hb != 0 && nowMs - hb <= Protocol.HEARTBEAT_TIMEOUT_MS;
	}

	// ------------------------------------------------------------------ triple buffer

	/**
	 * Initialises the triple-buffer state for a fresh mapping: middle = 1, writer back = 2,
	 * reader front = 0. Call once by the creator, before any publish/acquire.
	 */
	public void initTripleBuffer() {
		back = 2;
		front = 0;
		framesPublished = 0L;
		map.putInt((int) Protocol.CTL_STATE, 1);
		map.putLong((int) Protocol.CTL_FRAMES_PUBLISHED, 0L);
		map.putLong((int) Protocol.CTL_SEQUENCE, 0L);
		map.putLong((int) Protocol.CTL_TIMESTAMP, 0L);

		// Host-frame (host -> MC) triple buffer: this side is the reader.
		hostFrameFront = 0;
		map.putInt((int) Protocol.HOSTFRAME_CTL_STATE, 1);
		map.putLong((int) Protocol.HOSTFRAME_CTL_FRAMES_PUBLISHED, 0L);
		map.putLong((int) Protocol.HOSTFRAME_CTL_SEQUENCE, 0L);
		map.putLong((int) Protocol.HOSTFRAME_CTL_TIMESTAMP, 0L);
	}

	/**
	 * Reader side (Minecraft) of the host-frame triple buffer: returns the newest ready slot, or
	 * {@code -1} if no fresh host frame is available. Never blocks the host (writer).
	 */
	public int hostFrameAcquire() {
		int state = (int) STATE.getVolatile(map, (int) Protocol.HOSTFRAME_CTL_STATE);

		if ((state & Protocol.OVERLAY_FRESH) == 0) {
			return -1;
		}

		int old = (int) STATE.getAndSet(map, (int) Protocol.HOSTFRAME_CTL_STATE, hostFrameFront);
		hostFrameFront = old & Protocol.OVERLAY_INDEX_MASK;
		return old & Protocol.OVERLAY_INDEX_MASK;
	}

	public int hostFrameFrontSlot() {
		return hostFrameFront;
	}

	public int hostFrameSlotWidth(int slot) {
		return map.getInt((int) Protocol.hostFrameSlotHdr(slot) + 0);
	}

	public int hostFrameSlotHeight(int slot) {
		return map.getInt((int) Protocol.hostFrameSlotHdr(slot) + 4);
	}

	public int hostFrameSlotStride(int slot) {
		return map.getInt((int) Protocol.hostFrameSlotHdr(slot) + 8);
	}

	public int hostFrameSlotFormat(int slot) {
		return map.getInt((int) Protocol.hostFrameSlotHdr(slot) + 12);
	}

	public int hostFrameSlotFlags(int slot) {
		return map.getInt((int) Protocol.hostFrameSlotHdr(slot) + 16);
	}

	public long hostFrameSlotSequence(int slot) {
		return map.getLong((int) Protocol.hostFrameSlotHdr(slot) + 32);
	}

	public long hostFrameSlotTimestampMs(int slot) {
		return map.getLong((int) Protocol.hostFrameSlotHdr(slot) + 40);
	}

	/** Copies a host-frame slot's pixels into {@code dst} ({@code stride * height} bytes). */
	public void hostFrameReadPixels(int slot, byte[] dst) {
		map.get((int) Protocol.hostFrameSlotPixels(slot), dst, 0, dst.length);
	}

	public long hostFrameSequence() {
		return map.getLong((int) Protocol.HOSTFRAME_CTL_SEQUENCE);
	}

	public long hostFrameFramesPublished() {
		return map.getLong((int) Protocol.HOSTFRAME_CTL_FRAMES_PUBLISHED);
	}

	/** Reader side: returns the slot to read now, or -1 if no fresh frame is available. */
	public int acquire() {
		int state = (int) STATE.getVolatile(map, (int) Protocol.CTL_STATE);

		if ((state & Protocol.OVERLAY_FRESH) == 0) {
			return -1;
		}

		int old = (int) STATE.getAndSet(map, (int) Protocol.CTL_STATE, front);
		front = old & Protocol.OVERLAY_INDEX_MASK;
		return old & Protocol.OVERLAY_INDEX_MASK;
	}

	/** Reader side: the slot most recently acquired. */
	public int frontSlot() {
		return front;
	}

	/** Validates a frame geometry and returns the packed byte count. */
	private static int frameBytes(int width, int height) {
		if (width <= 0 || height <= 0 || width > Protocol.MAX_FRAME_W || height > Protocol.MAX_FRAME_H) {
			throw new IllegalArgumentException("frame size out of range: " + width + "x" + height);
		}

		return width * Protocol.BYTES_PER_PIXEL * height;
	}

	/** Writer side: publishes one packed BGRA frame. {@code bgra.length >= w*h*4}. */
	public void publishFrame(byte[] bgra, int width, int height, int flags) {
		int need = frameBytes(width, height);

		if (bgra.length < need) {
			throw new IllegalArgumentException("pixel buffer too small: " + bgra.length + " < " + need);
		}

		int slot = back;
		map.put((int) Protocol.slotPixels(slot), bgra, 0, need);
		commitFrame(slot, width, height, flags);
	}

	/**
	 * Writer side: publishes one packed BGRA frame from a <b>direct</b> buffer — e.g. the target
	 * of a {@code glReadPixels} call. This is the single-copy path used by the Minecraft frame
	 * exporter: pixels go straight from the GL readback buffer into the shared mapping.
	 *
	 * <p>{@code src} must be direct and hold at least {@code width*height*4} bytes; its position
	 * and limit are overwritten.
	 */
	public void publishFrame(ByteBuffer src, int width, int height, int flags) {
		if (!src.isDirect()) {
			throw new IllegalArgumentException("pixel buffer must be direct");
		}

		int need = frameBytes(width, height);

		if (src.capacity() < need) {
			throw new IllegalArgumentException("pixel buffer too small: " + src.capacity() + " < " + need);
		}

		int slot = back;
		src.position(0).limit(need);
		map.put((int) Protocol.slotPixels(slot), src, 0, need);
		commitFrame(slot, width, height, flags);
	}

	/** Writes the slot header and publishes {@code slot} with one atomic state swap. */
	private void commitFrame(int slot, int width, int height, int flags) {
		int stride = width * Protocol.BYTES_PER_PIXEL;
		long now = System.currentTimeMillis();
		long frameId = ++writeFrameId;
		long hdr = Protocol.slotHdr(slot);
		map.putInt((int) hdr + 0, width);
		map.putInt((int) hdr + 4, height);
		map.putInt((int) hdr + 8, stride);
		map.putInt((int) hdr + 12, Protocol.FORMAT_BGRA8);
		map.putInt((int) hdr + 16, flags);
		map.putInt((int) hdr + 20, slot);        // bufferIndex
		map.putLong((int) hdr + 24, frameId);    // frameId
		map.putLong((int) hdr + 32, frameId);    // sequence
		map.putLong((int) hdr + 40, now);        // timestampMs

		int old = (int) STATE.getAndSet(map, (int) Protocol.CTL_STATE, slot | Protocol.OVERLAY_FRESH);
		back = old & Protocol.OVERLAY_INDEX_MASK;

		map.putLong((int) Protocol.CTL_FRAMES_PUBLISHED, ++framesPublished);
		map.putLong((int) Protocol.CTL_SEQUENCE, frameId);
		map.putLong((int) Protocol.CTL_TIMESTAMP, now);
		map.putLong((int) Protocol.HDR_SEQUENCE, frameId);
		map.putLong((int) Protocol.HDR_TIMESTAMP, now);
	}

	// ------------------------------------------------------------------ seqlock state

	private int readSeq(long offset) {
		return (int) SEQ.getVolatile(map, (int) offset);
	}

	private void writeSeq(long offset, int value) {
		SEQ.setVolatile(map, (int) offset, value);
	}

	/** Host -> Minecraft. Single writer (the host). */
	public void writeHostState(HostState s) {
		long b = Protocol.OFF_HOST_STATE;
		int seq = readSeq(b + Protocol.HOST_SEQ);
		writeSeq(b + Protocol.HOST_SEQ, seq + 1);   // odd while writing
		VarHandle.fullFence();
		map.putInt((int) b + Protocol.HOST_FLAGS, s.flags);
		map.putInt((int) b + Protocol.HOST_WORLD_ID, s.worldId);
		map.putInt((int) b + Protocol.HOST_COLLISION_EPOCH, s.collisionEpoch);
		map.putLong((int) b + Protocol.HOST_TIMESTAMP, s.timestampMs);
		map.putDouble((int) b + Protocol.HOST_POS_X, s.posX);
		map.putDouble((int) b + Protocol.HOST_POS_Y, s.posY);
		map.putDouble((int) b + Protocol.HOST_POS_Z, s.posZ);
		map.putFloat((int) b + Protocol.HOST_YAW, s.yaw);
		map.putFloat((int) b + Protocol.HOST_PITCH, s.pitch);
		map.putFloat((int) b + Protocol.HOST_ROLL, s.roll);
		map.putFloat((int) b + Protocol.HOST_EYE_HEIGHT, s.eyeHeight);
		map.putFloat((int) b + Protocol.HOST_UNITS_PER_BLOCK, s.unitsPerBlock);
		map.putInt((int) b + Protocol.HOST_TELEPORT_SEQ, s.teleportSeq);
		map.putInt((int) b + Protocol.HOST_CAMERA_MODE, s.cameraMode);
		map.putInt((int) b + Protocol.HOST_VIEWPORT_W, s.viewportW);
		map.putInt((int) b + Protocol.HOST_VIEWPORT_H, s.viewportH);
		VarHandle.fullFence();
		writeSeq(b + Protocol.HOST_SEQ, seq + 2);   // even again
	}

	/** Reads a consistent HostState snapshot. Retries while the writer is mid-update. */
	public HostState readHostState() {
		long b = Protocol.OFF_HOST_STATE;

		for (int i = 0; i < SEQ_RETRIES; i++) {
			int s1 = readSeq(b + Protocol.HOST_SEQ);

			if ((s1 & 1) != 0) {
				continue;
			}

			VarHandle.acquireFence();
			HostState s = new HostState();
			s.flags = map.getInt((int) b + Protocol.HOST_FLAGS);
			s.worldId = map.getInt((int) b + Protocol.HOST_WORLD_ID);
			s.collisionEpoch = map.getInt((int) b + Protocol.HOST_COLLISION_EPOCH);
			s.timestampMs = map.getLong((int) b + Protocol.HOST_TIMESTAMP);
			s.posX = map.getDouble((int) b + Protocol.HOST_POS_X);
			s.posY = map.getDouble((int) b + Protocol.HOST_POS_Y);
			s.posZ = map.getDouble((int) b + Protocol.HOST_POS_Z);
			s.yaw = map.getFloat((int) b + Protocol.HOST_YAW);
			s.pitch = map.getFloat((int) b + Protocol.HOST_PITCH);
			s.roll = map.getFloat((int) b + Protocol.HOST_ROLL);
			s.eyeHeight = map.getFloat((int) b + Protocol.HOST_EYE_HEIGHT);
			s.unitsPerBlock = map.getFloat((int) b + Protocol.HOST_UNITS_PER_BLOCK);
			s.teleportSeq = map.getInt((int) b + Protocol.HOST_TELEPORT_SEQ);
			s.cameraMode = map.getInt((int) b + Protocol.HOST_CAMERA_MODE);
			s.viewportW = map.getInt((int) b + Protocol.HOST_VIEWPORT_W);
			s.viewportH = map.getInt((int) b + Protocol.HOST_VIEWPORT_H);
			VarHandle.fullFence();

			if (s1 == readSeq(b + Protocol.HOST_SEQ)) {
				return s;
			}
		}

		throw new IllegalStateException("HostState seqlock read failed after " + SEQ_RETRIES + " retries");
	}

	/** Minecraft -> host. Single writer (the Minecraft client). */
	public void writeMcState(McState s) {
		long b = Protocol.OFF_MC_STATE;
		int seq = readSeq(b + Protocol.MC_SEQ);
		writeSeq(b + Protocol.MC_SEQ, seq + 1);
		VarHandle.fullFence();
		map.putInt((int) b + Protocol.MC_FLAGS, s.flags);
		map.putLong((int) b + Protocol.MC_TIMESTAMP, s.timestampMs);
		map.putDouble((int) b + Protocol.MC_X, s.x);
		map.putDouble((int) b + Protocol.MC_Y, s.y);
		map.putDouble((int) b + Protocol.MC_Z, s.z);
		map.putDouble((int) b + Protocol.MC_PREV_X, s.prevX);
		map.putDouble((int) b + Protocol.MC_PREV_Y, s.prevY);
		map.putDouble((int) b + Protocol.MC_PREV_Z, s.prevZ);
		map.putDouble((int) b + Protocol.MC_CUR_X, s.curX);
		map.putDouble((int) b + Protocol.MC_CUR_Y, s.curY);
		map.putDouble((int) b + Protocol.MC_CUR_Z, s.curZ);
		map.putFloat((int) b + Protocol.MC_YAW, s.yaw);
		map.putFloat((int) b + Protocol.MC_PITCH, s.pitch);
		map.putFloat((int) b + Protocol.MC_EYE_HEIGHT, s.eyeHeight);
		map.putFloat((int) b + Protocol.MC_FOV_DEG, s.fovDeg);
		map.putFloat((int) b + Protocol.MC_TICK_MS, s.tickMs);
		map.putInt((int) b + Protocol.MC_CAMERA_MODE, s.cameraMode);
		map.putFloat((int) b + Protocol.MC_CAMERA_DISTANCE, s.cameraDistance);
		map.putLong((int) b + Protocol.MC_FRAME_COUNTER, s.frameCounter);
		map.putLong((int) b + Protocol.MC_TICK_QPC, s.tickQpc);
		map.putInt((int) b + Protocol.MC_HEALTH, s.health);
		map.putInt((int) b + Protocol.MC_HUNGER, s.hunger);
		VarHandle.fullFence();
		writeSeq(b + Protocol.MC_SEQ, seq + 2);
	}

	/** Reads a consistent McState snapshot. Retries while the writer is mid-update. */
	public McState readMcState() {
		long b = Protocol.OFF_MC_STATE;

		for (int i = 0; i < SEQ_RETRIES; i++) {
			int s1 = readSeq(b + Protocol.MC_SEQ);

			if ((s1 & 1) != 0) {
				continue;
			}

			VarHandle.acquireFence();
			McState s = new McState();
			s.flags = map.getInt((int) b + Protocol.MC_FLAGS);
			s.timestampMs = map.getLong((int) b + Protocol.MC_TIMESTAMP);
			s.x = map.getDouble((int) b + Protocol.MC_X);
			s.y = map.getDouble((int) b + Protocol.MC_Y);
			s.z = map.getDouble((int) b + Protocol.MC_Z);
			s.prevX = map.getDouble((int) b + Protocol.MC_PREV_X);
			s.prevY = map.getDouble((int) b + Protocol.MC_PREV_Y);
			s.prevZ = map.getDouble((int) b + Protocol.MC_PREV_Z);
			s.curX = map.getDouble((int) b + Protocol.MC_CUR_X);
			s.curY = map.getDouble((int) b + Protocol.MC_CUR_Y);
			s.curZ = map.getDouble((int) b + Protocol.MC_CUR_Z);
			s.yaw = map.getFloat((int) b + Protocol.MC_YAW);
			s.pitch = map.getFloat((int) b + Protocol.MC_PITCH);
			s.eyeHeight = map.getFloat((int) b + Protocol.MC_EYE_HEIGHT);
			s.fovDeg = map.getFloat((int) b + Protocol.MC_FOV_DEG);
			s.tickMs = map.getFloat((int) b + Protocol.MC_TICK_MS);
			s.cameraMode = map.getInt((int) b + Protocol.MC_CAMERA_MODE);
			s.cameraDistance = map.getFloat((int) b + Protocol.MC_CAMERA_DISTANCE);
			s.frameCounter = map.getLong((int) b + Protocol.MC_FRAME_COUNTER);
			s.tickQpc = map.getLong((int) b + Protocol.MC_TICK_QPC);
			s.health = map.getInt((int) b + Protocol.MC_HEALTH);
			s.hunger = map.getInt((int) b + Protocol.MC_HUNGER);
			VarHandle.fullFence();

			if (s1 == readSeq(b + Protocol.MC_SEQ)) {
				return s;
			}
		}

		throw new IllegalStateException("McState seqlock read failed after " + SEQ_RETRIES + " retries");
	}

	// ------------------------------------------------------------------ cross-space tables

	/** Zeroes the table/ring headers. Call once by the creator, next to {@link #initTripleBuffer()}. */
	public void initTables() {
		map.putInt((int) Protocol.OFF_COLLIDERS + 8, Protocol.COLLIDER_CAPACITY);
		map.putInt((int) Protocol.OFF_ENTITIES + 8, Protocol.ENTITY_CAPACITY);
		map.putInt((int) Protocol.OFF_DAMAGE + 8, Protocol.DAMAGE_CAPACITY);
		map.putInt((int) Protocol.OFF_INPUT_RING + 8, Protocol.INPUT_RING_ENTRIES);
		map.putInt((int) Protocol.OFF_BLOCK_EDITS + 8, Protocol.BLOCK_EDIT_CAPACITY);
		map.putInt((int) Protocol.OFF_INPUT_RING, 0);
		map.putInt((int) Protocol.OFF_INPUT_RING + 4, 0);

		map.putInt((int) Protocol.OFF_COLLIDERS, 0);
		map.putInt((int) Protocol.OFF_COLLIDERS + 4, 0);
		map.putInt((int) Protocol.OFF_ENTITIES, 0);
		map.putInt((int) Protocol.OFF_ENTITIES + 4, 0);
		map.putInt((int) Protocol.OFF_DAMAGE, 0);
		map.putInt((int) Protocol.OFF_DAMAGE + 4, 0);
		map.putInt((int) Protocol.OFF_BLOCK_EDITS, 0);
		map.putInt((int) Protocol.OFF_BLOCK_EDITS + 4, 0);
	}

	/** Host -> Minecraft. Rewrites the whole collider table under a seqlock. */
	public void writeColliderTable(Collider[] items) {
		long b = Protocol.OFF_COLLIDERS;
		int seq = readSeq(b);
		writeSeq(b, seq + 1);
		VarHandle.fullFence();

		int n = Math.min(items.length, Protocol.COLLIDER_CAPACITY);
		map.putInt((int) b + 4, n);
		map.putInt((int) b + 8, Protocol.COLLIDER_CAPACITY);
		map.putLong((int) b + 16, map.getLong((int) b + 16) + 1L);
		map.putLong((int) b + 24, System.currentTimeMillis());

		for (int i = 0; i < n; i++) {
			Collider c = items[i];
			long e = Protocol.OFF_COLLIDER_ENTRIES + (long) i * Protocol.COLLIDER_SIZE;
			map.putInt((int) e + 0, c.id);
			map.putInt((int) e + 4, c.type);
			map.putInt((int) e + 8, c.flags);
			map.putInt((int) e + 12, c.revision);
			map.putFloat((int) e + 16, c.centerX);
			map.putFloat((int) e + 20, c.centerY);
			map.putFloat((int) e + 24, c.centerZ);
			map.putFloat((int) e + 28, c.halfX);
			map.putFloat((int) e + 32, c.halfY);
			map.putFloat((int) e + 36, c.halfZ);
			map.putFloat((int) e + 40, c.rotYaw);
			map.putLong((int) e + 48, c.updatedMs == 0 ? System.currentTimeMillis() : c.updatedMs);
		}

		VarHandle.fullFence();
		writeSeq(b, seq + 2);
	}

	/** Reads a consistent collider table snapshot. */
	public Collider[] readColliderTable() {
		long b = Protocol.OFF_COLLIDERS;

		for (int attempt = 0; attempt < SEQ_RETRIES; attempt++) {
			int s1 = readSeq(b);

			if ((s1 & 1) != 0) {
				continue;
			}

			VarHandle.acquireFence();
			int n = map.getInt((int) b + 4);

			if (n < 0 || n > Protocol.COLLIDER_CAPACITY) {
				n = Math.max(0, Math.min(n, Protocol.COLLIDER_CAPACITY));
			}

			Collider[] out = new Collider[n];

			for (int i = 0; i < n; i++) {
				long e = Protocol.OFF_COLLIDER_ENTRIES + (long) i * Protocol.COLLIDER_SIZE;
				Collider c = new Collider();
				c.id = map.getInt((int) e + 0);
				c.type = map.getInt((int) e + 4);
				c.flags = map.getInt((int) e + 8);
				c.revision = map.getInt((int) e + 12);
				c.centerX = map.getFloat((int) e + 16);
				c.centerY = map.getFloat((int) e + 20);
				c.centerZ = map.getFloat((int) e + 24);
				c.halfX = map.getFloat((int) e + 28);
				c.halfY = map.getFloat((int) e + 32);
				c.halfZ = map.getFloat((int) e + 36);
				c.rotYaw = map.getFloat((int) e + 40);
				c.updatedMs = map.getLong((int) e + 48);
				out[i] = c;
			}

			VarHandle.fullFence();

			if (s1 == readSeq(b)) {
				return out;
			}
		}

		throw new IllegalStateException("ColliderTable seqlock read failed after " + SEQ_RETRIES + " retries");
	}

	/** Host -> Minecraft. Rewrites the whole entity table under a seqlock. */
	public void writeEntityTable(EntityMap[] items) {
		long b = Protocol.OFF_ENTITIES;
		int seq = readSeq(b);
		writeSeq(b, seq + 1);
		VarHandle.fullFence();

		int n = Math.min(items.length, Protocol.ENTITY_CAPACITY);
		map.putInt((int) b + 4, n);
		map.putInt((int) b + 8, Protocol.ENTITY_CAPACITY);
		map.putLong((int) b + 16, map.getLong((int) b + 16) + 1L);
		map.putLong((int) b + 24, System.currentTimeMillis());

		for (int i = 0; i < n; i++) {
			EntityMap m = items[i];
			long e = Protocol.OFF_ENTITY_ENTRIES + (long) i * Protocol.ENTITY_SIZE;
			map.putInt((int) e + 0, m.hostEntityId);
			map.putInt((int) e + 4, m.mcEntityId);
			map.putInt((int) e + 8, m.kind);
			map.putInt((int) e + 12, m.flags);
			map.putFloat((int) e + 16, m.x);
			map.putFloat((int) e + 20, m.y);
			map.putFloat((int) e + 24, m.z);
			map.putFloat((int) e + 28, m.yaw);
			map.putFloat((int) e + 32, m.pitch);
			map.putFloat((int) e + 36, m.health);
			map.putFloat((int) e + 40, m.maxHealth);
			map.putInt((int) e + 44, m.crossEntityId);
			map.putLong((int) e + 48, m.updatedMs == 0 ? System.currentTimeMillis() : m.updatedMs);
		}

		VarHandle.fullFence();
		writeSeq(b, seq + 2);
	}

	/** Reads a consistent entity table snapshot. */
	public EntityMap[] readEntityTable() {
		long b = Protocol.OFF_ENTITIES;

		for (int attempt = 0; attempt < SEQ_RETRIES; attempt++) {
			int s1 = readSeq(b);

			if ((s1 & 1) != 0) {
				continue;
			}

			VarHandle.acquireFence();
			int n = map.getInt((int) b + 4);

			if (n < 0 || n > Protocol.ENTITY_CAPACITY) {
				n = Math.max(0, Math.min(n, Protocol.ENTITY_CAPACITY));
			}

			EntityMap[] out = new EntityMap[n];

			for (int i = 0; i < n; i++) {
				long e = Protocol.OFF_ENTITY_ENTRIES + (long) i * Protocol.ENTITY_SIZE;
				EntityMap m = new EntityMap();
				m.hostEntityId = map.getInt((int) e + 0);
				m.mcEntityId = map.getInt((int) e + 4);
				m.kind = map.getInt((int) e + 8);
				m.flags = map.getInt((int) e + 12);
				m.x = map.getFloat((int) e + 16);
				m.y = map.getFloat((int) e + 20);
				m.z = map.getFloat((int) e + 24);
				m.yaw = map.getFloat((int) e + 28);
				m.pitch = map.getFloat((int) e + 32);
				m.health = map.getFloat((int) e + 36);
				m.maxHealth = map.getFloat((int) e + 40);
				m.crossEntityId = map.getInt((int) e + 44);
				m.updatedMs = map.getLong((int) e + 48);
				out[i] = m;
			}

			VarHandle.fullFence();

			if (s1 == readSeq(b)) {
				return out;
			}
		}

		throw new IllegalStateException("EntityTable seqlock read failed after " + SEQ_RETRIES + " retries");
	}

	/** Minecraft -> host. Pushes one damage event (single producer). */
	public void pushDamage(DamageEvent d) {
		long b = Protocol.OFF_DAMAGE;
		int head = map.getInt((int) b + 0);
		int idx = Math.floorMod(head, Protocol.DAMAGE_CAPACITY);
		long e = Protocol.OFF_DAMAGE_ENTRIES + (long) idx * Protocol.DAMAGE_EVENT_SIZE;
		map.putInt((int) e + 0, d.crossEntityId);
		map.putInt((int) e + 4, d.mcEntityId);
		map.putInt((int) e + 8, d.sourceType);
		map.putInt((int) e + 12, d.flags);
		map.putFloat((int) e + 16, d.amount);
		map.putInt((int) e + 20, d.attackerCrossId);
		map.putFloat((int) e + 24, d.x);
		map.putFloat((int) e + 28, d.y);
		map.putFloat((int) e + 32, d.z);
		map.putFloat((int) e + 36, d.knockbackX);
		map.putFloat((int) e + 40, d.knockbackZ);
		map.putLong((int) e + 48, d.sequence == 0 ? head + 1L : d.sequence);
		map.putLong((int) e + 56, d.timestampMs == 0 ? System.currentTimeMillis() : d.timestampMs);
		VarHandle.fullFence();
		map.putInt((int) b + 0, head + 1);
	}

	/** Host side: returns the next damage event, or {@code null} if none is pending. */
	public DamageEvent pollDamage() {
		long b = Protocol.OFF_DAMAGE;
		int head = map.getInt((int) b + 0);
		int tail = map.getInt((int) b + 4);

		if (tail >= head) {
			return null;
		}

		int idx = Math.floorMod(tail, Protocol.DAMAGE_CAPACITY);
		long e = Protocol.OFF_DAMAGE_ENTRIES + (long) idx * Protocol.DAMAGE_EVENT_SIZE;
		DamageEvent d = new DamageEvent();
		d.crossEntityId = map.getInt((int) e + 0);
		d.mcEntityId = map.getInt((int) e + 4);
		d.sourceType = map.getInt((int) e + 8);
		d.flags = map.getInt((int) e + 12);
		d.amount = map.getFloat((int) e + 16);
		d.attackerCrossId = map.getInt((int) e + 20);
		d.x = map.getFloat((int) e + 24);
		d.y = map.getFloat((int) e + 28);
		d.z = map.getFloat((int) e + 32);
		d.knockbackX = map.getFloat((int) e + 36);
		d.knockbackZ = map.getFloat((int) e + 40);
		d.sequence = map.getLong((int) e + 48);
		d.timestampMs = map.getLong((int) e + 56);
		map.putInt((int) b + 4, tail + 1);
		return d;
	}

	/** Host -> Minecraft. Pushes one input event (single producer, the host). */
	public void pushInput(InputEvent in) {
		long b = Protocol.OFF_INPUT_RING;
		int head = map.getInt((int) b + 0);
		int idx = Math.floorMod(head, Protocol.INPUT_RING_ENTRIES);
		long e = Protocol.OFF_INPUT_EVENTS + (long) idx * Protocol.INPUT_EVENT_SIZE;
		map.putInt((int) e + Protocol.INPUT_TYPE, in.type);
		map.putInt((int) e + Protocol.INPUT_CODE, in.code);
		map.putInt((int) e + Protocol.INPUT_A, in.a);
		map.putInt((int) e + Protocol.INPUT_B, in.b);
		map.putLong((int) e + Protocol.INPUT_TIMESTAMP, in.timestampMs == 0 ? System.currentTimeMillis() : in.timestampMs);
		map.putLong((int) e + Protocol.INPUT_SEQUENCE, in.sequence == 0 ? head + 1L : in.sequence);
		VarHandle.fullFence();
		map.putInt((int) b + 0, head + 1);
	}

	/** Minecraft side: returns the next input event, or {@code null} if none is pending. */
	public InputEvent pollInput() {
		long b = Protocol.OFF_INPUT_RING;
		int head = map.getInt((int) b + 0);
		int tail = map.getInt((int) b + 4);

		if (tail >= head) {
			return null;
		}

		int idx = Math.floorMod(tail, Protocol.INPUT_RING_ENTRIES);
		long e = Protocol.OFF_INPUT_EVENTS + (long) idx * Protocol.INPUT_EVENT_SIZE;
		InputEvent in = new InputEvent();
		in.type = map.getInt((int) e + Protocol.INPUT_TYPE);
		in.code = map.getInt((int) e + Protocol.INPUT_CODE);
		in.a = map.getInt((int) e + Protocol.INPUT_A);
		in.b = map.getInt((int) e + Protocol.INPUT_B);
		in.timestampMs = map.getLong((int) e + Protocol.INPUT_TIMESTAMP);
		in.sequence = map.getLong((int) e + Protocol.INPUT_SEQUENCE);
		map.putInt((int) b + 4, tail + 1);
		return in;
	}

	// ------------------------------------------------------------------ slot accessors

	public int slotWidth(int slot) {
		return map.getInt((int) Protocol.slotHdr(slot) + 0);
	}

	public int slotHeight(int slot) {
		return map.getInt((int) Protocol.slotHdr(slot) + 4);
	}

	public int slotStride(int slot) {
		return map.getInt((int) Protocol.slotHdr(slot) + 8);
	}

	public int slotFormat(int slot) {
		return map.getInt((int) Protocol.slotHdr(slot) + 12);
	}

	public int slotFlags(int slot) {
		return map.getInt((int) Protocol.slotHdr(slot) + 16);
	}

	public int slotBufferIndex(int slot) {
		return map.getInt((int) Protocol.slotHdr(slot) + 20);
	}

	public long slotFrameId(int slot) {
		return map.getLong((int) Protocol.slotHdr(slot) + 24);
	}

	public long slotSequence(int slot) {
		return map.getLong((int) Protocol.slotHdr(slot) + 32);
	}

	public long slotTimestamp(int slot) {
		return map.getLong((int) Protocol.slotHdr(slot) + 40);
	}

	/** Copies a slot's pixels into {@code dst}. {@code dst.length} must be stride*height. */
	public void readPixels(int slot, byte[] dst) {
		map.get((int) Protocol.slotPixels(slot), dst, 0, dst.length);
	}

	@Override
	public void close() throws IOException {
		try {
			channel.close();
		} finally {
			raf.close();
		}
	}
}
