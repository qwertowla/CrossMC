package dev.crossmc.minecraft;

import dev.crossmc.bridge.BridgeMemory;
import dev.crossmc.bridge.InputEvent;
import dev.crossmc.bridge.Protocol;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * Consumes the CrossMC {@code InputRing} (host -> Minecraft) and injects it into Minecraft's own
 * input system — no custom movement/physics.
 *
 * <p>Keyboard: {@link KeyBinding#setPressed(boolean)} on Minecraft's real bindings
 * ({@code options.forwardKey}, ...), so {@code KeyboardInput.tick} + the native player tick compute
 * movement exactly as for a physical key. Mouse buttons: {@link KeyBinding#setKeyPressed} +
 * {@link KeyBinding#onKeyPressed} for the attack/use/pick bindings. Mouse look: the accumulated
 * delta is fed into {@code Mouse.cursorDeltaX/Y} by {@code MouseMixin}, so vanilla applies its own
 * sensitivity before {@code changeLookDirection}.
 *
 * <p>Lifecycle safety: held keys/buttons are tracked, {@link Protocol#INPUT_RELEASE_ALL} clears
 * them, and they are released automatically when the host is not alive or a GUI is open — so a host
 * crash can never leave W / Space / a mouse button stuck.
 */
public final class HostInputConsumer {
	private static final HostInputConsumer INSTANCE = new HostInputConsumer();

	private final IntOpenHashSet heldKeys = new IntOpenHashSet();
	private final IntOpenHashSet heldButtons = new IntOpenHashSet();
	private volatile double mouseDx;
	private volatile double mouseDy;
	private long lastSequence;
	private boolean hadInput;

	private HostInputConsumer() {
	}

	public static HostInputConsumer get() {
		return INSTANCE;
	}

	/** Client thread. {@code client} is used to reach the real options/player. */
	public void tick(MinecraftClient client, BridgeMemory memory) {
		if (memory == null || client.options == null) {
			return;
		}

		// A GUI is open: host input must not drive the player; release anything we hold.
		if (client.currentScreen != null) {
			releaseAll(client.options);
			return;
		}

		if (!memory.hostAlive(System.currentTimeMillis())) {
			releaseAll(client.options);
			return;
		}

		for (int guard = 0; guard < 512; guard++) {
			InputEvent event = memory.pollInput();

			if (event == null) {
				break;
			}

			if (event.sequence != 0 && event.sequence <= lastSequence) {
				continue; // already seen (wrap / duplicate)
			}

			lastSequence = Math.max(lastSequence, event.sequence);
			apply(client, client.options, event);
		}
	}

	private void apply(MinecraftClient client, GameOptions options, InputEvent event) {
		if (!hadInput) {
			hadInput = true;
			CrossMcMinecraft.LOGGER.info("CrossMC: injecting host input into Minecraft");
		}

		switch (event.type) {
			case Protocol.INPUT_KEY_DOWN, Protocol.INPUT_KEY_HOLD -> {
				KeyBinding binding = bindingFor(options, event.code);

				if (binding != null) {
					binding.setPressed(true);
					heldKeys.add(event.code);
				}
			}
			case Protocol.INPUT_KEY_UP -> {
				KeyBinding binding = bindingFor(options, event.code);

				if (binding != null) {
					binding.setPressed(false);
					heldKeys.remove(event.code);
				}
			}
			case Protocol.INPUT_MOUSE_DOWN -> {
				InputUtil.Key key = InputUtil.Type.MOUSE.createFromCode(event.code);
				KeyBinding.setKeyPressed(key, true);
				KeyBinding.onKeyPressed(key);
				heldButtons.add(event.code);
			}
			case Protocol.INPUT_MOUSE_UP -> {
				KeyBinding.setKeyPressed(InputUtil.Type.MOUSE.createFromCode(event.code), false);
				heldButtons.remove(event.code);
			}
			case Protocol.INPUT_MOUSE_MOVE -> {
				mouseDx += event.a;
				mouseDy += event.b;
			}
			case Protocol.INPUT_MOUSE_WHEEL -> {
				if (client.player != null) {
					client.player.getInventory().scrollInHotbar(Math.signum((double) event.a));
				}
			}
			case Protocol.INPUT_CURSOR_POS -> {
				// Recorded for future UI use; not applied to the player.
			}
			case Protocol.INPUT_RELEASE_ALL -> releaseAll(options);
			default -> CrossMcMinecraft.LOGGER.debug("CrossMC: unknown input type {}", event.type);
		}
	}

	/** Maps a CrossMC keyboard semantic to the real Minecraft binding. */
	private static KeyBinding bindingFor(GameOptions options, int code) {
		return switch (code) {
			case Protocol.KEY_FORWARD -> options.forwardKey;
			case Protocol.KEY_BACK -> options.backKey;
			case Protocol.KEY_LEFT -> options.leftKey;
			case Protocol.KEY_RIGHT -> options.rightKey;
			case Protocol.KEY_JUMP -> options.jumpKey;
			case Protocol.KEY_SNEAK -> options.sneakKey;
			case Protocol.KEY_SPRINT -> options.sprintKey;
			case Protocol.KEY_INVENTORY -> options.inventoryKey;
			case Protocol.KEY_DROP -> options.dropKey;
			case Protocol.KEY_SWAP_HANDS -> options.swapHandsKey;
			default -> null;
		};
	}

	/** Releases every key/button CrossMC injected (disconnect, GUI open, RELEASE_ALL). */
	private void releaseAll(GameOptions options) {
		if (!heldKeys.isEmpty()) {
			for (int code : heldKeys) {
				KeyBinding binding = bindingFor(options, code);

				if (binding != null) {
					binding.setPressed(false);
				}
			}

			heldKeys.clear();
		}

		if (!heldButtons.isEmpty()) {
			for (int code : heldButtons) {
				KeyBinding.setKeyPressed(InputUtil.Type.MOUSE.createFromCode(code), false);
			}

			heldButtons.clear();
		}
	}

	public boolean isKeyHeld(int code) {
		return heldKeys.contains(code);
	}

	public boolean isButtonHeld(int code) {
		return heldButtons.contains(code);
	}

	/** Drains the accumulated host mouse delta (consumed by {@code MouseMixin}). */
	public double drainMouseDx() {
		double value = mouseDx;
		mouseDx = 0;
		return value;
	}

	public double drainMouseDy() {
		double value = mouseDy;
		mouseDy = 0;
		return value;
	}
}
