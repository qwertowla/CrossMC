package dev.crossmc.minecraft;

import dev.crossmc.bridge.Protocol;

/**
 * Minecraft-side render placement for the host frame (the world-space quad). This is the CLIENT half
 * of the host-frame video path; the host capture settings live in the host adapter's own config.
 *
 * <p>Coordinates are <b>Minecraft world space</b> and never go through a host-adapter coordinate
 * mapper: the quad is an ordinary object in the Minecraft world. Read from the CrossMC config
 * ({@code crossmc.properties}) with the {@code render.world.*} keys.
 */
public final class ClientRenderConfig {
	public static boolean worldEnabled = true;
	public static double x = 0;
	public static double y = 20;
	public static double z = 0;
	public static float width = 16f;
	public static float height = 9f;
	public static boolean facePlayer = true;

	private ClientRenderConfig() {
	}

	public static void load() {
		worldEnabled = bool("render.world.enabled", true);
		x = number("render.world.x", 0);
		y = number("render.world.y", 20);
		z = number("render.world.z", 0);
		width = (float) number("render.world.width", 16);
		height = (float) number("render.world.height", 9);
		facePlayer = bool("render.world.facePlayer", true);
	}

	private static boolean bool(String key, boolean fallback) {
		String s = Protocol.configValue(key);

		if (s == null) {
			return fallback;
		}

		return s.equalsIgnoreCase("true") || s.equals("1");
	}

	private static double number(String key, double fallback) {
		String s = Protocol.configValue(key);

		if (s == null) {
			return fallback;
		}

		try {
			return Double.parseDouble(s.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
