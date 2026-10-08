package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.InputEvent;
import dev.crossmc.bridge.Protocol;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Consumes the CrossMC {@code InputRing} (host -> Minecraft).
 *
 * <p>This is the Host -> Minecraft player channel: the host only <b>captures</b> keyboard/mouse
 * input; Minecraft decides what it means. The consumer keeps a held-key / held-button state,
 * accumulates mouse delta and wheel, de-duplicates by {@code sequence}, honours
 * {@link Protocol#INPUT_RELEASE_ALL}, and clears everything when the host disconnects — so no key
 * can get stuck.
 *
 * <p><b>Status:</b> the ring is fully consumed into a typed {@link #isKeyHeld(int)} state here, but
 * it is <b>not yet applied</b> to the Minecraft player (that is the next step: translate this state
 * into Minecraft's own input/movement). The Minecraft player therefore remains driven by real
 * Minecraft input; McState stays authoritative.
 */
public final class HostInputConsumer {
	private static final HostInputConsumer INSTANCE = new HostInputConsumer();

	private final IntOpenHashSet heldKeys = new IntOpenHashSet();
	private final IntOpenHashSet heldButtons = new IntOpenHashSet();
	private int mouseDx;
	private int mouseDy;
	private int wheel;
	private int cursorX = -1;
	private int cursorY = -1;
	private long lastSequence;
	private boolean hadInput;

	private HostInputConsumer() {
	}

	public static HostInputConsumer get() {
		return INSTANCE;
	}

	/** Client thread: drains the ring and updates the input state. */
	public void tick(BridgeMemory memory) {
		if (memory == null) {
			return;
		}

		if (!memory.hostAlive(System.currentTimeMillis())) {
			if (!heldKeys.isEmpty() || !heldButtons.isEmpty()) {
				clear();
				CrossMcMinecraft.LOGGER.info("CrossMC: host gone — host input released");
			}

			return;
		}

		for (int guard = 0; guard < 512; guard++) {
			InputEvent event = memory.pollInput();

			if (event == null) {
				break;
			}

			if (event.sequence != 0 && event.sequence <= lastSequence) {
				continue; // already seen (ring wrap / duplicate)
			}

			lastSequence = Math.max(lastSequence, event.sequence);
			apply(event);
		}
	}

	private void apply(InputEvent event) {
		if (!hadInput) {
			hadInput = true;
			CrossMcMinecraft.LOGGER.info("CrossMC: receiving host input");
		}

		switch (event.type) {
			case Protocol.INPUT_KEY_DOWN, Protocol.INPUT_KEY_HOLD -> heldKeys.add(event.code);
			case Protocol.INPUT_KEY_UP -> heldKeys.remove(event.code);
			case Protocol.INPUT_MOUSE_DOWN -> heldButtons.add(event.code);
			case Protocol.INPUT_MOUSE_UP -> heldButtons.remove(event.code);
			case Protocol.INPUT_MOUSE_MOVE -> {
				mouseDx += event.a;
				mouseDy += event.b;
			}
			case Protocol.INPUT_MOUSE_WHEEL -> wheel += event.a;
			case Protocol.INPUT_CURSOR_POS -> {
				cursorX = event.a;
				cursorY = event.b;
			}
			case Protocol.INPUT_RELEASE_ALL -> clear();
			default -> CrossMcMinecraft.LOGGER.debug("CrossMC: unknown input type {}", event.type);
		}
	}

	private void clear() {
		heldKeys.clear();
		heldButtons.clear();
		mouseDx = 0;
		mouseDy = 0;
		wheel = 0;
	}

	public boolean isKeyHeld(int code) {
		return heldKeys.contains(code);
	}

	public boolean isButtonHeld(int code) {
		return heldButtons.contains(code);
	}

	public int cursorX() {
		return cursorX;
	}

	public int cursorY() {
		return cursorY;
	}

	public int drainMouseDx() {
		int value = mouseDx;
		mouseDx = 0;
		return value;
	}

	public int drainMouseDy() {
		int value = mouseDy;
		mouseDy = 0;
		return value;
	}

	public int drainWheel() {
		int value = wheel;
		wheel = 0;
		return value;
	}
}
