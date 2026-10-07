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
 * <p>One mapping carries everything (see {@link Protocol}). Cross-thread and cross-process
 * coordination uses plain memory access only: a lock-free <b>triple buffer</b> for frames and
 * (later) seqlock slots for small state. The writer (Minecraft) owns {@code back}; the reader
 * (host) owns {@code front}; they meet only at the atomic {@code state} word.
 *
 * <p>Not thread-safe by itself beyond the described single-writer / single-reader roles.
 */
public final class BridgeMemory implements Closeable {
	// Atomic int view over the mapped bytes. Index is a BYTE offset.
	private static final VarHandle STATE =
			MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.nativeOrder());

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

	/**
	 * Opens (creating and sizing if necessary) the shared mapping. The file is created under
	 * {@code %LOCALAPPDATA%\CrossMC\bridge_v1.bin}.
	 */
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

	public boolean hasValidHeader() {
		return map.getInt((int) Protocol.HDR_MAGIC) == Protocol.MAGIC
				&& map.getInt((int) Protocol.HDR_VERSION) == Protocol.VERSION;
	}

	/** Writes the header. Call once by whichever side creates the mapping. */
	public void initHeader(int hostPid, int mcPid) {
		map.putInt((int) Protocol.HDR_MAGIC, Protocol.MAGIC);
		map.putInt((int) Protocol.HDR_VERSION, Protocol.VERSION);
		map.putInt((int) Protocol.HDR_MAPPING_BYTES, (int) Protocol.MAPPING_BYTES);
		map.putInt((int) Protocol.HDR_FLAGS, 0);
		map.putInt((int) Protocol.HDR_HOST_PID, hostPid);
		map.putInt((int) Protocol.HDR_MC_PID, mcPid);
		map.putLong((int) Protocol.HDR_HOST_HEARTBEAT, 0L);
		map.putLong((int) Protocol.HDR_MC_HEARTBEAT, 0L);
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
		long hdr = Protocol.slotHdr(slot);
		map.putInt((int) hdr + 0, width);
		map.putInt((int) hdr + 4, height);
		map.putInt((int) hdr + 8, stride);
		map.putInt((int) hdr + 12, Protocol.FORMAT_BGRA8);
		map.putInt((int) hdr + 16, flags);
		map.putLong((int) hdr + 24, ++writeFrameId);
		map.putLong((int) hdr + 32, System.currentTimeMillis());

		int old = (int) STATE.getAndSet(map, (int) Protocol.CTL_STATE, slot | Protocol.OVERLAY_FRESH);
		back = old & Protocol.OVERLAY_INDEX_MASK;

		map.putLong((int) Protocol.CTL_FRAMES_PUBLISHED, ++framesPublished);
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

	public long slotFrameId(int slot) {
		return map.getLong((int) Protocol.slotHdr(slot) + 24);
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
