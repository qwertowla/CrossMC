package dev.crossmc.bridge;

/**
 * Java mirror of {@code crossmc_input_event} in {@code protocol/bridge_protocol.h}.
 *
 * <p>Produced by the host (input source) and consumed by Minecraft, which interprets it and
 * computes the player state. {@link #sequence} is monotonic: it orders events and lets the
 * consumer de-duplicate, and {@link Protocol#INPUT_RELEASE_ALL} clears any stuck held input.
 */
public final class InputEvent {
	public int type;                 // Protocol.INPUT_*
	public int code;                 // key / mouse button code
	public int a;                    // value 1 (dx, wheel delta, cursor x, ...)
	public int b;                    // value 2 (dy, cursor y, ...)
	public long timestampMs;
	public long sequence;            // monotonic event id

	public InputEvent() {
	}

	public InputEvent(int type, int code, int a, int b) {
		this.type = type;
		this.code = code;
		this.a = a;
		this.b = b;
	}
}
