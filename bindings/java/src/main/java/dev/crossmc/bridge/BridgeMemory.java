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
			MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());
	private static final VarHandle SEQ =
			MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());

	private static final int SEQ_RETRIES = 256;

	private final RandomAccessFile raf;
	private final FileChannel channel;
	private final MappedByteBuffer map;

	// private roles
	private int back;    // writer's private write slot
	private int front;   // reader's private read slot
	private long writeFrameId;
	private long framesPublished;

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
		map.putInt((int) Protocol.HDR_FLAGS, 0);
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
			VarHandle.fullFence();

			if (s1 == readSeq(b + Protocol.MC_SEQ)) {
				return s;
			}
		}

		throw new IllegalStateException("McState seqlock read failed after " + SEQ_RETRIES + " retries");
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
